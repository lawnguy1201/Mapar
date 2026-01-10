package com.example.addon.modules;


/***
 * :::NOTE:::
 * Code Originally Written by bebeli555
 * Bot was made for Bewtiched and given to me
 * only made for version 1.19
 *
 * :::NEED TO DO::::
 * Add support for current 2b MC version (1.21.5)
 * Fix and upgrade speed
 * Add support for meteor (add all the settings)
 * Make it so you can use any platform (need to have a way to recognise
 * the boundaries on the chests for carpet and clear)
 * Add fullblock support staircase in the future
 * Remove bebeli555 imports and create new ones (they are very bloated an unoptimized)
 * Need to hook up with baritone
 *
 */

import com.google.common.eventbus.Subscribe;
import me.bebeli555.automapart.Mod;
import me.bebeli555.automapart.events.render.Render3DEvent;
import me.bebeli555.automapart.gui.Group;
import me.bebeli555.automapart.settings.*;
import me.bebeli555.automapart.utils.*;
import me.bebeli555.automapart.utils.globalsettings.GlobalPosRenderSettings;
import me.bebeli555.automapart.utils.objects.Timer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;


public class MapartBot {


    public class AutoMapart extends Mod {
        public static Setting schematicIndex = new Setting(Mode.INTEGER, "SchematicIndex", 1, "Index of the schematic its currently building, when its finished it will increase this", "And start the next mapart, when there are no more schematics it will exit");
        public static Setting schematicX = new Setting(Mode.INTEGER, "X", 0, "X coordinate for the schematic placement");
        public static Setting schematicY = new Setting(Mode.INTEGER, "Y", 0, "Y coordinate for the schematic placement");
        public static Setting schematicZ = new Setting(Mode.INTEGER, "Z", 0, "Z coordinate for the schematic placement");
        public static Setting schematicDirection = new Setting(Mode.INTEGER, "Direction", new SettingValue(1, 1, 8, 1), "Direction of the schematic placement", "The numbers dont have any real meaning just try different ones to find the right one");
        public static Setting chatDebug = new Setting(Mode.BOOLEAN, "ChatDebug", true, "Prints debug information to chat", "Like what its currently doing etc");
        public static Setting renderBlocks = new Setting(Mode.BOOLEAN, "RenderBlocks", false, "If enabled, just renders the schematic to the world", "And removes rendered blocks after disabled");
        public static Setting baritoneSleepSeconds = new Setting(Mode.DOUBLE, "MappingSleep", new SettingValue(5, 0, 60, 0.1), "How many seconds to sleep after making baritone walk to", "unmapped area to map out the schematic at the start");
        public static Setting resetBlockCache = new Setting(Mode.BOOLEAN, "ResetBlockCache", true, "Resets the block cache when the mod is disabled", "This is useful if you want to map out the schematic again");
        public static Setting itemTakeDelay = new Setting(Mode.DOUBLE, "ItemTakeDelay", new SettingValue(0.35, 0, 3, 0.01), "How many seconds to wait between taking items from chest");
        public static Setting placeSpeed = new Setting(Mode.DOUBLE, "PlaceSpeed", new SettingValue(0.1, 0, 1, 0.001), "How many seconds to wait between placing blocks");
        public static Setting rotationWait = new Setting(Mode.DOUBLE, "RotationWait", new SettingValue(0.1, 0, 1, 0.001), "How many seconds to wait after rotating to a block before placing it");
        public static Setting placeDistance = new Setting(Mode.DOUBLE, "PlaceDistance", new SettingValue(4, 0, 8, 0.05), "How many blocks away from the player to place the blocks at max");
        public static Setting cacheUpdateDelay = new Setting(Mode.DOUBLE, "CacheUpdateDelay", new SettingValue(0.5, 0, 15, 0.1), "How often in seconds to update the block cache");
        public static Setting attemptedResetDelay = new Setting(Mode.DOUBLE, "AttemptResetDelay", new SettingValue(15, 0, 1000, 1), "How often in seconds to reset a blacklist of failed attempted placement positions");
        public static Setting stationX = new Setting(Mode.INTEGER, "StationX", 0, "X coordinate for the station with cartography table, barrel, and xp, glass, map dispensers", "Must be set exactly to the position of the button below the dispensers");
        public static Setting stationY = new Setting(Mode.INTEGER, "StationY", 0, "Y coordinate for the station with cartography table, barrel, and xp, glass, map dispensers", "Must be set exactly to the position of the button below the dispensers");
        public static Setting stationZ = new Setting(Mode.INTEGER, "StationZ", 0, "Z coordinate for the station with cartography table, barrel, and xp, glass, map dispensers", "Must be set exactly to the position of the button below the dispensers");
        public static Setting resetX = new Setting(Mode.INTEGER, "ResetX", 0, "X coordinate below the oak button that when pressed resets the mapart machine", "with water dispensers");
        public static Setting resetY = new Setting(Mode.INTEGER, "ResetY", 0, "Y coordinate below the oak button that when pressed resets the mapart machine", "with water dispensers");
        public static Setting resetZ = new Setting(Mode.INTEGER, "ResetZ", 0, "Z coordinate below the oak button that when pressed resets the mapart machine", "with water dispensers");

        public SchematicReader schematicReader;
        public Map<BlockPos, BlockState> blocksCache = new HashMap<>();
        public Timer blocksCacheTimer = new Timer();
        public Block blockToPlace;
        public Map<BlockPos, Integer> attemptedPlacements = new HashMap<>();
        public Timer attemptedPlacementsResetTimer = new Timer();
        public boolean initialized, enableAgain;
        public boolean finished, resetMachine;

        public AutoMapart() {
            super(Group.WORLD, "AutoMapart", "Automated carpet mapart bot made for bewitched", "The schematics need to be in /schematics folder and end with .nbt", "They need to start with mapart-, after the - comes the index of that schematic", "so like mapart-1.nbt, mapart-2.nbt, mapart-3.nbt etc", "It will start building the first and move to the second after its done and the map for it has been locked", "After the schematic is built it will lock the map and reset the mapart machine with water");
            WorldRenderEvents.BEFORE_ENTITIES.register(this::onRenderLastWorld);

            new Thread(() -> {
                while(true) {
                    try {
                        loop();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    sleep(5);
                }
            }).start();
        }

        @Override
        public void onEnabled() {
            try {
                File file = new File(mc.runDirectory.getPath() + "/schematics/mapart-" + schematicIndex.asInt() + ".nbt");
                if (!file.exists()) {
                    if (chatDebug.bool()) sendMessage("No schematic with index " + schematicIndex.asInt() + " found, exiting", Formatting.RED);
                    disable();
                    return;
                }

                if (resetBlockCache.bool()) blocksCache.clear();
                blocksCacheTimer.ms = 0;
                blockToPlace = null;
                attemptedPlacements.clear();
                finished = false;
                resetMachine = false;

                schematicReader = new SchematicReader();
                schematicReader.load(file);

                if (chatDebug.bool()) sendMessage("Successfully loaded " + file.getName() + " schematic with " + schematicReader.list.size() + " blocks");

                int purged = 0;
                List<SchematicReader.SchematicBlock> tempList = new ArrayList<>(schematicReader.list);
                for (SchematicReader.SchematicBlock schematicBlock : tempList) {
                    if (!(schematicBlock.block instanceof CarpetBlock)) {
                        purged++;
                        schematicReader.list.remove(schematicBlock);
                    } else {
                        if (schematicDirection.asInt() == 1) {
                            schematicBlock.pos = new BlockPos(schematicBlock.pos.getX(), schematicBlock.pos.getY(), -schematicBlock.pos.getZ());
                        } else if (schematicDirection.asInt() == 2) {
                            schematicBlock.pos = new BlockPos(-schematicBlock.pos.getX(), schematicBlock.pos.getY(), schematicBlock.pos.getZ());
                        } else if (schematicDirection.asInt() == 3) {
                            schematicBlock.pos = new BlockPos(-schematicBlock.pos.getX(), schematicBlock.pos.getY(), -schematicBlock.pos.getZ());
                        } else if (schematicDirection.asInt() == 4) {
                            schematicBlock.pos = new BlockPos(schematicBlock.pos.getX(), schematicBlock.pos.getY(), schematicBlock.pos.getZ());
                        } else if (schematicDirection.asInt() == 5) {
                            schematicBlock.pos = new BlockPos(schematicBlock.pos.getZ(), schematicBlock.pos.getY(), -schematicBlock.pos.getX());
                        } else if (schematicDirection.asInt() == 6) {
                            schematicBlock.pos = new BlockPos(-schematicBlock.pos.getZ(), schematicBlock.pos.getY(), schematicBlock.pos.getX());
                        } else if (schematicDirection.asInt() == 7) {
                            schematicBlock.pos = new BlockPos(-schematicBlock.pos.getZ(), schematicBlock.pos.getY(), -schematicBlock.pos.getX());
                        } else if (schematicDirection.asInt() == 8) {
                            schematicBlock.pos = new BlockPos(schematicBlock.pos.getZ(), schematicBlock.pos.getY(), schematicBlock.pos.getX());
                        }
                    }
                }

                if (purged > 0) {
                    if (chatDebug.bool()) sendMessage("Purged " + purged + " non carpet blocks from schematic");
                }

                initialized = true;
            } catch (Exception e) {
                if (chatDebug.bool()) sendMessage("Failed to load schematic, more info in console", Formatting.RED);
                e.printStackTrace();
            }
        }

        @Override
        public void onDisabled() {
            initialized = false;
        }

        @Subscribe
        public void onRenderLastWorld(WorldRenderContext context) {
            if (this.isOn() && renderBlocks.bool()) {
                for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                    context.matrixStack().push();
                    Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
                    BlockPos pos = schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt());
                    context.matrixStack().translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                    mc.getBlockRenderManager().renderBlockAsEntity(schematicBlock.block.getDefaultState(), context.matrixStack(), mc.getBufferBuilders().getEntityVertexConsumers(), 0xF000F0, OverlayTexture.DEFAULT_UV);
                    context.matrixStack().pop();
                }
            }
        }

        public void loop() {
            if (mc.player == null && this.isOn()) {
                enableAgain = true;
            }

            if (!this.isOn() || mc.player == null || mc.world == null || renderBlocks.bool() || !initialized) {
                return;
            }

            if (enableAgain) {
                enableAgain = false;
                run(this::onEnabled);
                sleep(1000);
            }

            if (finished) {
                if (resetMachine) {
                    BlockPos resetPos = new BlockPos(resetX.asInt(), resetY.asInt(), resetZ.asInt());
                    if (BlockUtils.distance(mc.player.getBlockPos(), resetPos) > 1) {
                        if (chatDebug.bool()) sendMessage("Moving to reset button");
                        BaritoneUtils.goTo(resetPos);
                        sleep(1000);
                        sleepUntil(() -> !BaritoneUtils.isPathing(), 60000);
                        sleep(1500);
                    } else {
                        if (chatDebug.bool()) sendMessage("Pressing reset button");
                        BlockPos button = BlockUtils.findBlock(mc.player.getPos(), Blocks.OAK_BUTTON, 4);
                        run(() -> RotationUtils.rotateTo(button.add(0, 1, 0), 0, Direction.DOWN));
                        sleep(1000);
                        run(PlayerUtils::rightClick);
                        schematicIndex.setValue(schematicIndex.asInt() + 1);
                        Settings.saveSettings();
                        if (chatDebug.bool()) sendMessage("Finished current mapart, increased index, now waiting while for water to go");
                        run(this::onEnabled);
                        sleepUntil(() -> !this.isOn(), 120000);
                    }
                } else {
                    boolean firstCheck = false;
                    for (Slot slot : mc.player.playerScreenHandler.slots) {
                        if (Block.getBlockFromItem(slot.getStack().getItem()) instanceof CarpetBlock) {
                            if (chatDebug.bool() && !firstCheck) sendMessage("Throwing carpets from inventory");
                            if (!firstCheck) {
                                run(() -> RotationUtils.rotateTo(mc.player.getBlockPos().add(2, 1, 0)));
                                sleep(250);
                            }

                            firstCheck = true;

                            run(() -> InventoryUtils.throwAway(slot.id));
                            sleep(200);
                        }
                    }

                    if (firstCheck) return;
                    BlockPos stationPos = new BlockPos(stationX.asInt(), stationY.asInt(), stationZ.asInt());

                    boolean hasLockedMap = false;
                    for (Slot slot : mc.player.playerScreenHandler.slots) {
                        if (slot.getStack().getItem() == Items.FILLED_MAP) {
                            ItemStack stack = slot.getStack();
                            if (stack.hasNbt() && stack.getNbt().contains("map")) {
                                int mapId = stack.getNbt().getInt("map");
                                // Get the map state using the map ID
                                MapState mapState = ((FilledMapItem)stack.getItem()).getMapState(mapId, mc.world);
                                if (mapState != null && mapState.locked) {
                                    hasLockedMap = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (hasLockedMap) {
                        if (mc.currentScreen instanceof GenericContainerScreen) {
                            if (chatDebug.bool()) sendMessage("Putting locked map to barrel");
                            for (Slot slot : mc.player.currentScreenHandler.slots) {
                                if (slot.id > 26 && slot.getStack().getItem() == Items.FILLED_MAP) {
                                    run(() -> InventoryUtils.quickMove(slot.id));
                                    sleep(1000);
                                    break;
                                }
                            }

                            if (InventoryUtils.getAmountOfItem(Items.FILLED_MAP) == 0) {
                                resetMachine = true;
                            }
                        } else {
                            if (chatDebug.bool()) sendMessage("Opening output barrel");
                            BlockPos barrel = BlockUtils.findBlock(mc.player.getPos(), Blocks.BARREL, 2);
                            run(() -> RotationUtils.rotateTo(barrel));
                            sleep(500);
                            run(PlayerUtils::rightClick);
                            sleep(1000);
                        }
                    } else if (mc.currentScreen instanceof CartographyTableScreen) {
                        if (chatDebug.bool()) sendMessage("Locking map with cartography table");
                        for (Slot slot : mc.player.currentScreenHandler.slots) {
                            if (slot.id > 2 && (slot.getStack().getItem() == Items.GLASS_PANE || slot.getStack().getItem() == Items.FILLED_MAP)) {
                                run(() -> InventoryUtils.quickMove(slot.id));
                                sleep(500);
                            }
                        }

                        sleep(1000);
                        run(() -> InventoryUtils.quickMove(2));
                        sleep(1000);
                        run(() -> mc.currentScreen.close());
                        run(() -> mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId)));
                        sleep(1500);
                    } else if (BlockUtils.distance(mc.player.getBlockPos(), stationPos) > 1) {
                        if (chatDebug.bool()) sendMessage("Schematic finished, moving to station to lock map");
                        BaritoneUtils.goTo(stationPos);
                        sleep(1000);
                        sleepUntil(() -> !BaritoneUtils.isPathing(), 60000);
                    } else if ((InventoryUtils.getAmountOfItem(Items.MAP) == 0 && InventoryUtils.getAmountOfItem(Items.FILLED_MAP) == 0) || InventoryUtils.getAmountOfItem(Items.GLASS_PANE) == 0) {
                        if (chatDebug.bool()) sendMessage("No map or glass in inventory, clicking button");
                        BlockPos button = BlockUtils.findBlock(mc.player.getPos(), Blocks.OAK_BUTTON, 2);
                        run(() -> RotationUtils.rotateTo(button.add(0, -1, 0)));
                        sleep(500);
                        run(PlayerUtils::rightClick);
                        sleep(3500);
                    } else if (InventoryUtils.getAmountOfItem(Items.FILLED_MAP) == 0) {
                        if (chatDebug.bool()) sendMessage("Filling empty map by clicking it");
                        run(() -> InventoryUtils.switchToItem(Items.MAP));
                        sleep(500);
                        run(() -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND));
                        sleep(1500);
                    } else {
                        if (chatDebug.bool()) sendMessage("Opening cartography table");
                        BlockPos cartographyTable = BlockUtils.findBlock(mc.player.getPos(), Blocks.CARTOGRAPHY_TABLE, 2);
                        run(() -> RotationUtils.rotateTo(cartographyTable));
                        sleep(500);
                        run(PlayerUtils::rightClick);
                        sleep(1000);
                    }
                }

                return;
            }

            if (attemptedPlacementsResetTimer.hasPassed((int)(attemptedResetDelay.asDouble() * 1000))) {
                attemptedPlacements.clear();
                attemptedPlacementsResetTimer.reset();
            }

            if (blocksCacheTimer.hasPassed((int)(cacheUpdateDelay.asDouble() * 1000))) {
                blocksCacheTimer.reset();
                for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                    BlockState state = mc.world.getBlockState(schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt()));
                    if (state.getBlock() != Blocks.VOID_AIR) {
                        blocksCache.put(schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt()), state);
                    }
                }
            }

            if (blocksCache.size() < 14000) {
                if (chatDebug.bool()) sendMessage("Render distance low, walking to map out the schematic blocks: " + blocksCache.size());
                for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                    BlockPos pos = schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt());
                    if (!blocksCache.containsKey(pos)) {
                        BaritoneUtils.goTo(pos);
                        sleep((int)(baritoneSleepSeconds.asDouble() * 1000));
                        return;
                    }
                }
            }

            if (blockToPlace == null) {
                Map<Block, Integer> blockCounts = new HashMap<>();
                for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                    BlockPos pos = schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt());
                    if (blocksCache.containsKey(pos) && blocksCache.get(pos).getBlock() != Blocks.AIR) {
                        continue;
                    }

                    Block block = schematicBlock.block;
                    blockCounts.put(block, blockCounts.getOrDefault(block, 0) + 1);
                }

                if (blockCounts.isEmpty()) {
                    finished = true;
                    return;
                }

                blockToPlace = blockCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                if (chatDebug.bool()) sendMessage("Now placing " + blockToPlace.getName().getString() + " x" + blockCounts.get(blockToPlace));
            }

            boolean rotated = false;
            for (Slot slot : mc.player.playerScreenHandler.slots) {
                if (Block.getBlockFromItem(slot.getStack().getItem()) instanceof CarpetBlock) {
                    if (slot.getStack().getItem() != Item.fromBlock(blockToPlace)) {
                        if (!rotated) {
                            rotated = true;
                            sendMessage("Throwing wrong carpets from inventory");
                            run(() -> RotationUtils.rotateTo(mc.player.getBlockPos().add(2, 1, 0)));
                            sleep(150);
                        }

                        run(() -> InventoryUtils.throwAway(slot.id));
                        sleep(100);
                    }
                }
            }

            if (rotated) {
                run(() -> mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId)));
            }

            if (InventoryUtils.getAmountOfItem(Item.fromBlock(blockToPlace)) == 0) {
                for (int x = -100; x < 100; x++) {
                    for (int z = -100; z < 100; z++) {
                        for (int y = -5; y < 5; y++) {
                            BlockPos pos = mc.player.getBlockPos().add(x, y, z);
                            BlockState state = mc.world.getBlockState(pos);
                            if ((state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST) && mc.world.getBlockState(pos.add(0, 1, 0)).getBlock() == blockToPlace) {
                                if (BlockUtils.distance(mc.player.getBlockPos(), pos) <= 4) {
                                    if (mc.currentScreen instanceof GenericContainerScreen) {
                                        if (chatDebug.bool()) sendMessage("Taking " + blockToPlace.getName().getString() + " from chest");

                                        Timer timer = new Timer();
                                        timer.reset();
                                        while (InventoryUtils.getFreeSpace() > 0) {
                                            if (timer.hasPassed(30000)) {
                                                break;
                                            }

                                            for (Slot slot : mc.player.currentScreenHandler.slots) {
                                                if (slot.id <= 53 && slot.getStack().getItem() == Item.fromBlock(blockToPlace)) {
                                                    run(() -> InventoryUtils.quickMove(slot.id));
                                                    sleep((int)(itemTakeDelay.asDouble() * 1000));
                                                    break;
                                                }
                                            }
                                        }

                                        if (mc.currentScreen instanceof GenericContainerScreen) {
                                            run(() -> mc.currentScreen.close());
                                            run(() -> mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId)));
                                        }
                                    } else {
                                        if (chatDebug.bool()) sendMessage("Opening " + blockToPlace.getName().getString() + " chest");
                                        run(() -> RotationUtils.rotateTo(pos, -0.3));
                                        sleep(500);
                                        run(PlayerUtils::rightClick);
                                        sleep(1000);
                                    }
                                } else {
                                    if (chatDebug.bool()) sendMessage(blockToPlace.getName().getString() + " chest found, moving closer to it");

                                    BlockPos safePos = pos.add(1, 0, 0);
                                    if (mc.world.getBlockState(pos.add(-1, 0, 0)).getBlock() == Blocks.AIR && mc.world.getBlockState(pos.add(-1, 1, 0)).getBlock() == Blocks.AIR) {
                                        safePos = pos.add(-1, 0, 0);
                                    } else if (mc.world.getBlockState(pos.add(0, 0, 1)).getBlock() == Blocks.AIR && mc.world.getBlockState(pos.add(0, 1, 1)).getBlock() == Blocks.AIR) {
                                        safePos = pos.add(0, 0, 1);
                                    } else if (mc.world.getBlockState(pos.add(0, 0, -1)).getBlock() == Blocks.AIR && mc.world.getBlockState(pos.add(0, 1, -1)).getBlock() == Blocks.AIR) {
                                        safePos = pos.add(0, 0, -1);
                                    }

                                    BaritoneUtils.goTo(safePos);
                                    sleep(1000);
                                    sleepUntil(() -> !BaritoneUtils.isPathing(), 30000);
                                }

                                return;
                            }
                        }
                    }
                }

                if (chatDebug.bool()) sendMessage("No " + blockToPlace.getName().getString() + " chest found, moving to corner");
                BaritoneUtils.goTo(schematicReader.list.get(0).pos);
                sleep(5000);
                return;
            }

            List<BlockPos> placePositions = new ArrayList<>();
            for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                BlockPos pos = schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt());
                if (pos.equals(mc.player.getBlockPos())) {
                    continue;
                }

                if (schematicBlock.block != blockToPlace) {
                    continue;
                }

                if (BlockUtils.distance(mc.player.getBlockPos(), pos) <= 25 && mc.world.getBlockState(pos).getBlock() != Blocks.AIR) {
                    continue;
                }

                if (attemptedPlacements.containsKey(pos)) {
                    continue;
                }

                if (BlockUtils.distanceToPlayer(pos) <= placeDistance.asDouble() && (!blocksCache.containsKey(pos) || blocksCache.get(pos).getBlock() == Blocks.AIR)) {
                    placePositions.add(pos);
                    attemptedPlacements.put(pos, attemptedPlacements.getOrDefault(pos, 0) + 1);
                }
            }

            if (placePositions.isEmpty()) {
                BlockPos closest = null;
                for (SchematicReader.SchematicBlock schematicBlock : schematicReader.list) {
                    BlockPos pos = schematicBlock.pos.add(schematicX.asInt(), schematicY.asInt(), schematicZ.asInt());
                    if (BlockUtils.distance(mc.player.getBlockPos(), pos) <= 2) {
                        continue;
                    }

                    if (schematicBlock.block != blockToPlace) {
                        continue;
                    }

                    if (attemptedPlacements.containsKey(pos)) {
                        continue;
                    }

                    if (blocksCache.containsKey(pos) && blocksCache.get(pos).getBlock() == Blocks.AIR) {
                        if (closest == null || BlockUtils.distance(mc.player.getBlockPos(), pos) < BlockUtils.distance(mc.player.getBlockPos(), closest)) {
                            closest = pos;
                        }
                    }
                }

                if (closest == null) {
                    blockToPlace = null;
                    return;
                }

                if (mc.world.getBlockState(closest.add(1, 0, 0)).getBlock() == blockToPlace) {
                    closest = closest.add(1, 0, 0);
                } else if (mc.world.getBlockState(closest.add(-1, 0, 0)).getBlock() == blockToPlace) {
                    closest = closest.add(-1, 0, 0);
                } else if (mc.world.getBlockState(closest.add(0, 0, 1)).getBlock() == blockToPlace) {
                    closest = closest.add(0, 0, 1);
                } else if (mc.world.getBlockState(closest.add(0, 0, -1)).getBlock() == blockToPlace) {
                    closest = closest.add(0, 0, -1);
                } else {
                    int xDirection = Integer.compare(mc.player.getBlockPos().getX(), closest.getX());
                    int zDirection = Integer.compare(mc.player.getBlockPos().getZ(), closest.getZ());
                    closest = closest.add(xDirection, 0, zDirection);
                }

                sendMessage("Moving to closest spot to place " + blockToPlace.getName().getString());
                BaritoneUtils.goTo(closest);
                sleep(1000);
                sleepUntil(() -> !BaritoneUtils.isPathing(), 60000);
                sleep(250);
            } else {
                sendMessage("Placing " + placePositions.size() + " " + blockToPlace.getName().getString() + " around the player");

                run(() -> mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY)));
                sleep(350);

                Collections.sort(placePositions, Comparator.comparingInt(pos -> BlockUtils.distance(mc.player.getBlockPos(), pos)));
                Collections.reverse(placePositions);

                for (BlockPos pos : placePositions) {
                    if (InventoryUtils.getHotbarSlot(Item.fromBlock(blockToPlace)) != mc.player.getInventory().selectedSlot) {
                        run(() -> InventoryUtils.switchToItem(Item.fromBlock(blockToPlace)));
                        sleep(50);
                    }

                    run(() -> RotationUtils.rotateTo(pos.add(0, -1, 0)));
                    sleep((int)(rotationWait.asDouble() * 1000));

                    if (mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult hitResult = (BlockHitResult)mc.crosshairTarget;
                        if (mc.world.getBlockState(hitResult.getBlockPos()).getBlock() == blockToPlace) {
                            continue;
                        }
                    }

                    run(PlayerUtils::rightClick);
                    sleep((int)(placeSpeed.asDouble() * 1000));
                }

                sleep(100);
                run(() -> mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)));
            }
        }
    }


}

