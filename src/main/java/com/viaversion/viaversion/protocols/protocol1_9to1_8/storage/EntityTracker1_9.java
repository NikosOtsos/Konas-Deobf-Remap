package com.viaversion.viaversion.protocols.protocol1_9to1_8.storage;

import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.legacy.bossbar.BossBar;
import com.viaversion.viaversion.api.legacy.bossbar.BossColor;
import com.viaversion.viaversion.api.legacy.bossbar.BossStyle;
import com.viaversion.viaversion.api.minecraft.Position;
import com.viaversion.viaversion.api.minecraft.entities.Entity1_10Types;
import com.viaversion.viaversion.api.minecraft.entities.EntityType;
import com.viaversion.viaversion.api.minecraft.item.DataItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.metadata.Metadata;
import com.viaversion.viaversion.api.minecraft.metadata.types.MetaType1_9;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.version.Types1_9;
import com.viaversion.viaversion.data.entity.EntityTrackerBase;
import com.viaversion.viaversion.libs.opennbt.tag.builtin.CompoundTag;
import com.viaversion.viaversion.protocols.protocol1_9to1_8.Protocol1_9To1_8;
import com.viaversion.viaversion.protocols.protocol1_9to1_8.chat.GameMode;
import com.viaversion.viaversion.protocols.protocol1_9to1_8.metadata.MetadataRewriter1_9To1_8;
import com.viaversion.viaversion.protocols.protocol1_9to1_8.providers.BossBarProvider;
import com.viaversion.viaversion.protocols.protocol1_9to1_8.providers.EntityIdProvider;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class EntityTracker1_9 extends EntityTrackerBase {
   private final Map uuidMap = new ConcurrentHashMap();
   private final Map metadataBuffer = new ConcurrentHashMap();
   private final Map vehicleMap = new ConcurrentHashMap();
   private final Map bossBarMap = new ConcurrentHashMap();
   private final Set validBlocking = Sets.newConcurrentHashSet();
   private final Set knownHolograms = Sets.newConcurrentHashSet();
   private final Set blockInteractions;
   private boolean blocking;
   private boolean autoTeam;
   private Position currentlyDigging;
   private boolean teamExists;
   private GameMode gameMode;
   private String currentTeam;
   private int heldItemSlot;
   private Item itemInSecondHand;

   public EntityTracker1_9(UserConnection user) {
      super(user, Entity1_10Types.EntityType.PLAYER);
      this.blockInteractions = Collections.newSetFromMap(CacheBuilder.newBuilder().maximumSize(1000L).expireAfterAccess(250L, TimeUnit.MILLISECONDS).build().asMap());
      this.blocking = false;
      this.autoTeam = false;
      this.currentlyDigging = null;
      this.teamExists = false;
      this.itemInSecondHand = null;
   }

   public UUID getEntityUUID(int id) {
      UUID uuid = (UUID)this.uuidMap.get(id);
      if (uuid == null) {
         uuid = UUID.randomUUID();
         this.uuidMap.put(id, uuid);
      }

      return uuid;
   }

   public void setSecondHand(Item item) {
      this.setSecondHand(this.clientEntityId(), item);
   }

   public void setSecondHand(int entityID, Item item) {
      PacketWrapper wrapper = PacketWrapper.create(60, null, this.user());
      wrapper.write(Type.VAR_INT, entityID);
      wrapper.write(Type.VAR_INT, 1);
      wrapper.write(Type.ITEM, this.itemInSecondHand = item);

      try {
         wrapper.scheduleSend(Protocol1_9To1_8.class);
      } catch (Exception var5) {
         var5.printStackTrace();
      }

   }

   public Item getItemInSecondHand() {
      return this.itemInSecondHand;
   }

   public void syncShieldWithSword() {
      boolean swordInHand = this.hasSwordInHand();
      if (!swordInHand || this.itemInSecondHand == null) {
         this.setSecondHand(swordInHand ? new DataItem(442, (byte)1, (short)0, null) : null);
      }

   }

   public boolean hasSwordInHand() {
      InventoryTracker inventoryTracker = (InventoryTracker)this.user().get(InventoryTracker.class);
      int inventorySlot = this.heldItemSlot + 36;
      int itemIdentifier = inventoryTracker.getItemId((short)0, (short)inventorySlot);
      return Protocol1_9To1_8.isSword(itemIdentifier);
   }

   public void removeEntity(int entityId) {
      super.removeEntity(entityId);
      this.vehicleMap.remove(entityId);
      this.uuidMap.remove(entityId);
      this.validBlocking.remove(entityId);
      this.knownHolograms.remove(entityId);
      this.metadataBuffer.remove(entityId);
      BossBar bar = (BossBar)this.bossBarMap.remove(entityId);
      if (bar != null) {
         bar.hide();
         ((BossBarProvider)Via.getManager().getProviders().get(BossBarProvider.class)).handleRemove(this.user(), bar.getId());
      }

   }

   public boolean interactedBlockRecently(int x, int y, int z) {
      return this.blockInteractions.contains(new Position(x, (short)y, z));
   }

   public void addBlockInteraction(Position p) {
      this.blockInteractions.add(p);
   }

   public void handleMetadata(int entityId, List metadataList) {
      EntityType type = this.entityType(entityId);
      if (type != null) {
         Iterator var4 = (new ArrayList(metadataList)).iterator();

         while(true) {
            Metadata metadata;
            do {
               do {
                  if (!var4.hasNext()) {
                     return;
                  }

                  metadata = (Metadata)var4.next();
                  if (type == Entity1_10Types.EntityType.WITHER && metadata.method_71() == 10) {
                     metadataList.remove(metadata);
                  }

                  if (type == Entity1_10Types.EntityType.ENDER_DRAGON && metadata.method_71() == 11) {
                     metadataList.remove(metadata);
                  }

                  if (type == Entity1_10Types.EntityType.SKELETON && this.getMetaByIndex(metadataList, 12) == null) {
                     metadataList.add(new Metadata(12, MetaType1_9.Boolean, true));
                  }

                  if (type == Entity1_10Types.EntityType.HORSE && metadata.method_71() == 16 && (Integer)metadata.getValue() == Integer.MIN_VALUE) {
                     metadata.setValue(0);
                  }

                  if (type == Entity1_10Types.EntityType.PLAYER) {
                     if (metadata.method_71() == 0) {
                        byte data = (Byte)metadata.getValue();
                        if (entityId != this.getProvidedEntityId() && Via.getConfig().isShieldBlocking()) {
                           if ((data & 16) == 16) {
                              if (this.validBlocking.contains(entityId)) {
                                 Item shield = new DataItem(442, (byte)1, (short)0, null);
                                 this.setSecondHand(entityId, shield);
                              } else {
                                 this.setSecondHand(entityId, null);
                              }
                           } else {
                              this.setSecondHand(entityId, null);
                           }
                        }
                     }

                     if (metadata.method_71() == 12 && Via.getConfig().isLeftHandedHandling()) {
                        metadataList.add(new Metadata(13, MetaType1_9.Byte, (byte)(((Byte)metadata.getValue() & 128) != 0 ? 0 : 1)));
                     }
                  }

                  if (type == Entity1_10Types.EntityType.ARMOR_STAND && Via.getConfig().isHologramPatch() && metadata.method_71() == 0 && this.getMetaByIndex(metadataList, 10) != null) {
                     Metadata meta = this.getMetaByIndex(metadataList, 10);
                     byte data = (Byte)metadata.getValue();
                     Metadata displayName;
                     Metadata displayNameVisible;
                     if ((data & 32) == 32 && ((Byte)meta.getValue() & 1) == 1 && (displayName = this.getMetaByIndex(metadataList, 2)) != null && !((String)displayName.getValue()).isEmpty() && (displayNameVisible = this.getMetaByIndex(metadataList, 3)) != null && (Boolean)displayNameVisible.getValue() && !this.knownHolograms.contains(entityId)) {
                        this.knownHolograms.add(entityId);

                        try {
                           PacketWrapper wrapper = PacketWrapper.create(37, null, this.user());
                           wrapper.write(Type.VAR_INT, entityId);
                           wrapper.write(Type.SHORT, Short.valueOf((short)0));
                           wrapper.write(Type.SHORT, (short)((int)(128.0D * Via.getConfig().getHologramYOffset() * 32.0D)));
                           wrapper.write(Type.SHORT, Short.valueOf((short)0));
                           wrapper.write(Type.BOOLEAN, true);
                           wrapper.scheduleSend(Protocol1_9To1_8.class);
                        } catch (Exception var11) {
                        }
                     }
                  }
               } while(!Via.getConfig().isBossbarPatch());
            } while(type != Entity1_10Types.EntityType.ENDER_DRAGON && type != Entity1_10Types.EntityType.WITHER);

            BossBar bar;
            if (metadata.method_71() == 2) {
               bar = (BossBar)this.bossBarMap.get(entityId);
               String title = (String)metadata.getValue();
               title = title.isEmpty() ? (type == Entity1_10Types.EntityType.ENDER_DRAGON ? "Ender Dragon" : "Wither") : title;
               if (bar == null) {
                  bar = Via.getAPI().legacyAPI().createLegacyBossBar(title, BossColor.PINK, BossStyle.SOLID);
                  this.bossBarMap.put(entityId, bar);
                  bar.addConnection(this.user());
                  bar.show();
                  ((BossBarProvider)Via.getManager().getProviders().get(BossBarProvider.class)).handleAdd(this.user(), bar.getId());
               } else {
                  bar.setTitle(title);
               }
            } else if (metadata.method_71() == 6 && !Via.getConfig().isBossbarAntiflicker()) {
               bar = (BossBar)this.bossBarMap.get(entityId);
               float maxHealth = type == Entity1_10Types.EntityType.ENDER_DRAGON ? 200.0F : 300.0F;
               float health = Math.max(0.0F, Math.min((Float)metadata.getValue() / maxHealth, 1.0F));
               if (bar == null) {
                  String title = type == Entity1_10Types.EntityType.ENDER_DRAGON ? "Ender Dragon" : "Wither";
                  bar = Via.getAPI().legacyAPI().createLegacyBossBar(title, health, BossColor.PINK, BossStyle.SOLID);
                  this.bossBarMap.put(entityId, bar);
                  bar.addConnection(this.user());
                  bar.show();
                  ((BossBarProvider)Via.getManager().getProviders().get(BossBarProvider.class)).handleAdd(this.user(), bar.getId());
               } else {
                  bar.setHealth(health);
               }
            }
         }
      }
   }

   public Metadata getMetaByIndex(List list, int index) {
      Iterator var3 = list.iterator();

      Metadata meta;
      do {
         if (!var3.hasNext()) {
            return null;
         }

         meta = (Metadata)var3.next();
      } while(index != meta.method_71());

      return meta;
   }

   public void sendTeamPacket(boolean add, boolean now) {
      PacketWrapper wrapper = PacketWrapper.create(65, null, this.user());
      wrapper.write(Type.STRING, "viaversion");
      if (add) {
         if (!this.teamExists) {
            wrapper.write(Type.BYTE, (byte)0);
            wrapper.write(Type.STRING, "viaversion");
            wrapper.write(Type.STRING, "§f");
            wrapper.write(Type.STRING, "");
            wrapper.write(Type.BYTE, (byte)0);
            wrapper.write(Type.STRING, "");
            wrapper.write(Type.STRING, "never");
            wrapper.write(Type.BYTE, (byte)15);
         } else {
            wrapper.write(Type.BYTE, (byte)3);
         }

         wrapper.write(Type.STRING_ARRAY, new String[]{this.user().getProtocolInfo().getUsername()});
      } else {
         wrapper.write(Type.BYTE, (byte)1);
      }

      this.teamExists = add;

      try {
         if (now) {
            wrapper.send(Protocol1_9To1_8.class);
         } else {
            wrapper.scheduleSend(Protocol1_9To1_8.class);
         }
      } catch (Exception var5) {
         var5.printStackTrace();
      }

   }

   public void addMetadataToBuffer(int entityID, List metadataList) {
      List metadata = (List)this.metadataBuffer.get(entityID);
      if (metadata != null) {
         metadata.addAll(metadataList);
      } else {
         this.metadataBuffer.put(entityID, metadataList);
      }

   }

   public void sendMetadataBuffer(int entityId) {
      List metadataList = (List)this.metadataBuffer.get(entityId);
      if (metadataList != null) {
         PacketWrapper wrapper = PacketWrapper.create(57, null, this.user());
         wrapper.write(Type.VAR_INT, entityId);
         wrapper.write(Types1_9.METADATA_LIST, metadataList);
         ((MetadataRewriter1_9To1_8) Via.getManager().getProtocolManager().getProtocol(Protocol1_9To1_8.class).get(MetadataRewriter1_9To1_8.class)).handleMetadata(entityId, metadataList, this.user());
         this.handleMetadata(entityId, metadataList);
         if (!metadataList.isEmpty()) {
            try {
               wrapper.scheduleSend(Protocol1_9To1_8.class);
            } catch (Exception var5) {
               var5.printStackTrace();
            }
         }

         this.metadataBuffer.remove(entityId);
      }

   }

   public int getProvidedEntityId() {
      try {
         return ((EntityIdProvider)Via.getManager().getProviders().get(EntityIdProvider.class)).getEntityId(this.user());
      } catch (Exception var2) {
         return this.clientEntityId();
      }
   }

   public Map getUuidMap() {
      return this.uuidMap;
   }

   public Map getMetadataBuffer() {
      return this.metadataBuffer;
   }

   public Map getVehicleMap() {
      return this.vehicleMap;
   }

   public Map getBossBarMap() {
      return this.bossBarMap;
   }

   public Set getValidBlocking() {
      return this.validBlocking;
   }

   public Set getKnownHolograms() {
      return this.knownHolograms;
   }

   public Set getBlockInteractions() {
      return this.blockInteractions;
   }

   public boolean isBlocking() {
      return this.blocking;
   }

   public void setBlocking(boolean blocking) {
      this.blocking = blocking;
   }

   public boolean isAutoTeam() {
      return this.autoTeam;
   }

   public void setAutoTeam(boolean autoTeam) {
      this.autoTeam = autoTeam;
   }

   public Position getCurrentlyDigging() {
      return this.currentlyDigging;
   }

   public void setCurrentlyDigging(Position currentlyDigging) {
      this.currentlyDigging = currentlyDigging;
   }

   public boolean isTeamExists() {
      return this.teamExists;
   }

   public GameMode getGameMode() {
      return this.gameMode;
   }

   public void setGameMode(GameMode gameMode) {
      this.gameMode = gameMode;
   }

   public String getCurrentTeam() {
      return this.currentTeam;
   }

   public void setCurrentTeam(String currentTeam) {
      this.currentTeam = currentTeam;
   }

   public void setHeldItemSlot(int heldItemSlot) {
      this.heldItemSlot = heldItemSlot;
   }
}
