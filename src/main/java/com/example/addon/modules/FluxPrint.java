package com.example.addon.modules;
/*
    Made by lawnguy
    start: 1/15/26
    finished: 3/21/26
    last modified: 5/28/26
 */

// meteor
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import com.example.addon.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;

// litematica
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;

// baritone
import baritone.api.BaritoneAPI;

// minecraft
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

// utils
import utils.BaritoneUtils;
import utils.LogUtils;
import utils.MathUtils;
import utils.Timer;

// java
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;


public class FluxPrint extends Module
{
    public FluxPrint()
    {
        super(Addon.CATEGORY, "Flux Print", "A Carpet Mapar Bot That Works With Any Platform, " +
            "Given The correct Setup! :D");
    }

    // Gen settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fullBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("full block mode")
        .description("On for full-block maps (platform is mined to reset). Off for flat carpet maps (a reset button is pressed).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> schematicsIndex = sgGeneral.add(new IntSetting.Builder()
        .name("schematic-index")
        .description("Index of the schematic to start at (0 = first).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 5000)
        .build()
    );

    private final Setting<PlacementAlgo> placementAlgo = sgGeneral.add(new EnumSetting.Builder<PlacementAlgo>()
        .name("placement algorithm")
        .description("Order in which placeable clusters are visited. "
            + "FURTHEST_FIRST = back-of-platform first then nearest (default), "
            + "NEAREST_FIRST = always closest cluster, "
            + "SCANLINE = row-major sweep, "
            + "SPIRAL = from schematic center outward.")
        .defaultValue(PlacementAlgo.FURTHEST_FIRST)
        .build()
    );

    private final Setting<Boolean> fixErrors = sgGeneral.add(new BoolSetting.Builder()
        .name("fix placement errors")
        .description("When the printer leaves a WRONG block (not just a missing one), walk to it and break it so the printer can re-place correctly. Prevents the bot circling a mis-placed block forever.")
        .defaultValue(true)
        .build()
    );

    // timing shit
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Double> baritoneSleepSeconds = sgTiming.add(new DoubleSetting.Builder()
        .name("baritone arrival wait")
        .description("Seconds to pause after Baritone arrives at a destination (chests, cartography table, output, etc.).")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Double> clusterCheckInterval = sgTiming.add(new DoubleSetting.Builder()
        .name("cluster check interval")
        .description("Seconds between checks for whether the current placing cluster is finished. Lower = faster cluster-to-cluster transitions.")
        .defaultValue(0.15)
        .min(0.05)
        .sliderRange(0.05, 5)
        .build()
    );

    private final Setting<Double> clusterStuckTimeout = sgTiming.add(new DoubleSetting.Builder()
        .name("cluster stuck timeout")
        .description("If the bot stays at the same cluster this many seconds without migrating, nudge sideways to let the printer reach any block under the player's feet.")
        .defaultValue(5.0)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Double> itemTakeDelay = sgTiming.add(new DoubleSetting.Builder()
        .name("item take delay")
        .description("Seconds between chest clicks when restocking.")
        .defaultValue(0.65)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> errorBreakTimeout = sgTiming.add(new DoubleSetting.Builder()
        .name("error break timeout")
        .description("Maximum seconds to spend breaking a single wrong block before giving up on it (so a stubborn block doesn't create a new infinite loop).")
        .defaultValue(8.0)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Double> mapRenderTime = sgTiming.add(new DoubleSetting.Builder()
        .name("map render time")
        .description("Seconds to wait for the map to fully render after right-clicking.")
        .defaultValue(5.0)
        .min(0)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<Double> acceptedResetWait = sgTiming.add(new DoubleSetting.Builder()
        .name("platform reset wait")
        .description("Seconds to wait for the platform to reset after pressing the button (carpet mode).")
        .defaultValue(60)
        .min(0)
        .sliderRange(0, 300)
        .build()
    );

    private final Setting<Integer> clearAreaMaxSeconds = sgTiming.add(new IntSetting.Builder()
        .name("clear area max seconds")
        .description("Maximum seconds to wait for Baritone to finish clearing the platform (full-block mode).")
        .defaultValue(900)
        .min(30)
        .sliderRange(30, 3600)
        .build()
    );

    // restocking
    private final SettingGroup sgRestock = settings.createGroup("Restock Station");

    private final Setting<Block> platformBlock = sgRestock.add(new BlockSetting.Builder()
        .name("platform indicator block")
        .description("Block your restock platform is made of — chests adjacent to this block are ignored during the chest scan.")
        .defaultValue(Blocks.SEA_LANTERN)
        .build()
    );

    private final Setting<BlockPos> restockMin = sgRestock.add(new BlockPosSetting.Builder()
        .name("min corner")
        .description("Minimum corner of the restock station bounding box.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    private final Setting<BlockPos> restockMax = sgRestock.add(new BlockPosSetting.Builder()
        .name("max corner")
        .description("Maximum corner of the restock station bounding box.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    // schem placement
    private final SettingGroup sgSchem = settings.createGroup("Schematic");

    private final Setting<BlockPos> schemPos = sgSchem.add(new BlockPosSetting.Builder()
        .name("placement POS")
        .description("Origin (min corner) where the schematic is placed in the world.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    private final Setting<BlockPos> resetButtonPos = sgSchem.add(new BlockPosSetting.Builder()
        .name("reset button")
        .description("Position of the button that resets the platform (carpet mode only).")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    // Droping stuff
    private final SettingGroup sgDrop = settings.createGroup("Excess Drop");

    private final Setting<Boolean> dropExcessEnabled = sgDrop.add(new BoolSetting.Builder()
        .name("drop excess enabled")
        .description("Before every restock pass (including the start of each schematic), walk to the drop position and Q-drop any inventory items not required by the current schematic.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> dropExcessPos = sgDrop.add(new BlockPosSetting.Builder()
        .name("drop position")
        .description("Position to stand near while Q-dropping excess items. Put a void hopper / hopper-minecart here to collect them.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(dropExcessEnabled::get)
        .build()
    );

    // output input map chests cart tabel
    private final SettingGroup sgMap = settings.createGroup("Map Stations");

    private final Setting<BlockPos> mapChestPos = sgMap.add(new BlockPosSetting.Builder()
        .name("map chest")
        .description("Position of the chest holding empty maps and glass panes.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    private final Setting<BlockPos> cartTablePos = sgMap.add(new BlockPosSetting.Builder()
        .name("cartography table")
        .description("Position of the cartography table used to lock the map.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    private final Setting<BlockPos> outputChestPos = sgMap.add(new BlockPosSetting.Builder()
        .name("output chest")
        .description("Position of the output chest where locked maps are deposited.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );


    public enum BotState
    {
        IDLE,
        CONVERTING,
        INITIALIZING,
        ANALYZING,
        CHEST_SCAN,
        DROPPING_EXCESS,
        RESTOCKING,
        PLACING,
        CLEARING_ERRORS,
        VERIFYING,
        CAPTURE_MAP,
        LOCK_MAP,
        RESETTING_PLATFORM,
        MOVE_TO_OUTPUT,
        PAUSED,
        FINISHED
    }

    /***
     * PlacementAlgo selects the order in which placeable clusters are visited during PLACING.
     */
    public enum PlacementAlgo
    {
        FURTHEST_FIRST,
        NEAREST_FIRST,
        SCANLINE,
        SPIRAL
    }

    private BotState state = BotState.IDLE;

    private File modFolder;
    private File schematicsFolder;
    private File[] schemFiles;

    private LitematicaSchematic currentSchematic;
    private SchematicPlacement  currentPlacement;

    private final Map<Block, List<BlockPos>> chestMap     = new HashMap<>();
    private final Map<Item, Integer>         matNeeded    = new HashMap<>();
    private List<BlockPos>                   unplacedCache = new ArrayList<>();

    private Map<Item, Integer>               remainingMatsCache = new HashMap<>();

    private final Timer itemGrabTimer        = new Timer();
    private final Timer resetWaitTimer       = new Timer();
    private final Timer baritoneArrivalTimer = new Timer();
    private final Timer progressLogTimer     = new Timer();
    private final Timer unplacedCacheTimer   = new Timer();
    private final Timer clusterArrivalTimer  = new Timer();
    private final Timer mapRenderTimer       = new Timer();
    private final Timer baritoneStartTimer   = new Timer();
    private final Timer clearAreaTimer       = new Timer();
    private final Timer clusterStuckTimer    = new Timer();
    private final Timer errorBreakTimer      = new Timer();
    private       int   nudgeDirIdx          = 0;

    private boolean walkingToStation          = false;
    private boolean walkingToMapCenter        = false;
    private boolean walkingToMapChest         = false;
    private boolean resetButtonPressed        = false;
    private boolean walkingToRestButton       = false;
    private boolean mapGrabed                 = false;
    private boolean glassPaneGrabbed          = false;
    private boolean mapRightClicked           = false;
    private boolean walkingToCartographyTable = false;
    private boolean mapPlacedInTable          = false;
    private boolean glassPanePlacedInTable    = false;
    private boolean mapLocked                 = false;
    private boolean walkingToOutputChest      = false;
    private boolean clusterNearestToPlayer    = false;
    private boolean clearAreaStarted          = false;
    private boolean walkingToDropPos          = false;
    private boolean walkingToError            = false;
    private boolean tookFirstSlot             = false;

    private boolean baritoneArrived = false;

    private BlockPos        currentErrorTarget = null;
    private final Set<BlockPos> unbreakableErrors = new HashSet<>();

    private static final int  SCHEMATIC_SIZE    = 128;
    private static final int  SCHEMATIC_CENTER  = SCHEMATIC_SIZE / 2;
    private static final long UNPLACED_CACHE_MS = 250;
    private static final int  CLUSTER_SIZE      = 8;
    private static final int  FOOD_SLOT_COUNT   = 2;

    private int      totalBlocks          = 0;
    private int      currentChestIndex    = 0;
    private Item     currentGrabItem      = null;
    private BlockPos currentClusterTarget = null;


    private volatile CompletableFuture<Integer> conversionFuture;


    /***
     * convertSchematics() converts all .nbt structure files to .litematic format.
     *
     * The actual NBT parsing / file I/O runs on a background thread via CompletableFuture so 35+
     * files don't freeze the game on startup. This method is called from onTick: the first call
     * spawns the future, subsequent calls just poll until it resolves, then advance bot state.
     *
     */
    private void convertSchematics()
    {
        modFolder        = new File(MinecraftClient.getInstance().runDirectory, "FluxPrint");
        schematicsFolder = new File(modFolder, "Schematics");

        if (!schematicsFolder.exists())
        {
            if (!schematicsFolder.mkdirs())
            {
                LogUtils.error("Could Not Create Folder: " + schematicsFolder.getAbsolutePath());
                pause("Could Not Create Correct Folder");
                return;
            }
        }

        if (conversionFuture == null)
        {
            File[] nbtFiles = schematicsFolder.listFiles((dir, name) -> name.endsWith(".nbt"));

            if (nbtFiles == null || nbtFiles.length == 0)
            {
                LogUtils.log("No .nbt files found skipping conversion.");
                state = BotState.INITIALIZING;
                return;
            }

            // build the work list: only files that don't already have a matching .litematic
            List<File> toConvert = new ArrayList<>();
            for (File nbtFile : nbtFiles)
            {
                File litFile = new File(schematicsFolder, nbtFile.getName().replace(".nbt", ".litematic"));
                if (!litFile.exists()) toConvert.add(nbtFile);
            }

            if (toConvert.isEmpty())
            {
                LogUtils.log("All " + nbtFiles.length + " .nbt files already converted - skipping conversion.");
                state = BotState.INITIALIZING;
                return;
            }

            LogUtils.log("Converting " + toConvert.size() + " .nbt files to .litematic in the background...");
            state = BotState.CONVERTING;

            File outFolder = schematicsFolder;

            conversionFuture = CompletableFuture.supplyAsync(() ->
            {
                int converted = 0;
                int failed    = 0;
                for (File f : toConvert)
                {
                    try
                    {
                        convertOneFile(f, outFolder);
                        converted++;
                    }
                    catch (Exception e)
                    {
                        LogUtils.error("Error converting " + f.getName() + ": " + e.getMessage());
                        e.printStackTrace();
                        failed++;
                    }
                }
                LogUtils.log("Conversion done - " + converted + " converted, " + failed + " failed.");
                return failed;
            });
            return;
        }

        // === subsequent calls: poll until the background work finishes ===
        if (!conversionFuture.isDone()) return;

        int failed;
        try
        {
            failed = conversionFuture.get();
        }
        catch (Exception e)
        {
            LogUtils.error("Conversion future failed: " + e.getMessage());
            conversionFuture = null;
            pause("Conversion Threw Exception");
            return;
        }
        finally
        {
            conversionFuture = null;
        }

        if (failed > 0)
        {
            LogUtils.error(failed + " files failed - check logs.");
            pause("Conversion Failed For " + failed + " Files");
            return;
        }

        state = BotState.INITIALIZING;
    }

    /***
     * convertOneFile() does the actual .nbt -> .litematic conversion for a single file.
     * SAFE TO CALL FROM A BACKGROUND THREAD - touches only NBT data, file I/O, and the static
     * Block registry (which is fully populated by the time the bot starts).
     */
    private void convertOneFile(File nbtFile, File outFolder) throws Exception
    {
        String newName = nbtFile.getName().replace(".nbt", ".litematic");
        File   outFile = new File(outFolder, newName);

        if (outFile.exists()) return;

        NbtCompound structureNbt = NbtIo.readCompressed(
            nbtFile.toPath(),
            NbtSizeTracker.ofUnlimitedBytes()
        );

        NbtList sizeList = structureNbt.getList("size", NbtElement.INT_TYPE);
        int sizeX = sizeList.getInt(0);
        int sizeY = sizeList.getInt(1);
        int sizeZ = sizeList.getInt(2);

        int dataVersion = structureNbt.getInt("DataVersion");

        NbtList      paletteList   = structureNbt.getList("palette", NbtElement.COMPOUND_TYPE);
        BlockState[] structPalette = new BlockState[paletteList.size()];

        for (int i = 0; i < paletteList.size(); i++)
        {
            NbtCompound entry    = paletteList.getCompound(i);
            String      blockId  = entry.getString("Name");
            NbtCompound propsNbt = entry.contains("Properties")
                ? entry.getCompound("Properties") : null;

            Block      block = Registries.BLOCK.get(Identifier.of(blockId));
            BlockState bs    = block.getDefaultState();

            if (propsNbt != null)
            {
                StateManager<Block, BlockState> sm = block.getStateManager();
                for (String propName : propsNbt.getKeys())
                {
                    Property<?> prop = sm.getProperty(propName);
                    if (prop != null)
                    {
                        bs = applyProperty(bs, prop, propsNbt.getString(propName));
                    }
                }
            }
            structPalette[i] = bs;
        }

        int   volume     = sizeX * sizeY * sizeZ;
        int[] blockArray = new int[volume];

        Map<BlockState, Integer> stateToIndex = new LinkedHashMap<>();
        stateToIndex.put(Blocks.AIR.getDefaultState(), 0);

        NbtList blocksList = structureNbt.getList("blocks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < blocksList.size(); i++)
        {
            NbtCompound blockEntry = blocksList.getCompound(i);
            NbtList     pos        = blockEntry.getList("pos", NbtElement.INT_TYPE);
            int         stateIdx   = blockEntry.getInt("state");

            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);

            BlockState bs = structPalette[stateIdx];

            if (!stateToIndex.containsKey(bs))
            {
                stateToIndex.put(bs, stateToIndex.size());
            }

            int litIdx = y * (sizeX * sizeZ) + z * sizeX + x;
            blockArray[litIdx] = stateToIndex.get(bs);
        }

        NbtList blockStatePalette = new NbtList();
        for (BlockState bs : stateToIndex.keySet())
        {
            NbtCompound stateNbt = new NbtCompound();
            stateNbt.putString("Name", Registries.BLOCK.getId(bs.getBlock()).toString());

            NbtCompound props = new NbtCompound();
            for (Map.Entry<Property<?>, Comparable<?>> e : bs.getEntries().entrySet())
            {
                props.putString(e.getKey().getName(),
                    Util.getValueAsString(e.getKey(), e.getValue()));
            }
            if (!props.isEmpty()) stateNbt.put("Properties", props);
            blockStatePalette.add(stateNbt);
        }

        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(stateToIndex.size() - 1));
        LitematicaBitArray bitArray = new LitematicaBitArray(bits, volume);
        for (int i = 0; i < volume; i++)
        {
            bitArray.setAt(i, blockArray[i]);
        }

        int nonAirCount = 0;
        for (int v : blockArray) if (v != 0) nonAirCount++;

        NbtCompound regionPos = new NbtCompound();
        regionPos.putInt("x", 0);
        regionPos.putInt("y", 0);
        regionPos.putInt("z", 0);

        NbtCompound regionSize = new NbtCompound();
        regionSize.putInt("x", sizeX);
        regionSize.putInt("y", sizeY);
        regionSize.putInt("z", sizeZ);

        NbtCompound region = new NbtCompound();
        region.put("Position",          regionPos);
        region.put("Size",              regionSize);
        region.put("BlockStatePalette", blockStatePalette);
        region.put("BlockStates",       new NbtLongArray(bitArray.getBackingLongArray()));
        region.put("TileEntities",      new NbtList());
        region.put("Entities",          new NbtList());
        region.put("PendingBlockTicks", new NbtList());
        region.put("PendingFluidTicks", new NbtList());

        NbtCompound enclosingSize = new NbtCompound();
        enclosingSize.putInt("x", sizeX);
        enclosingSize.putInt("y", sizeY);
        enclosingSize.putInt("z", sizeZ);

        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name",        newName.replace(".litematic", ""));
        metadata.putString("Description", "");
        metadata.putString("Author",      "FluxPrint");
        metadata.putLong("TimeCreated",   System.currentTimeMillis());
        metadata.putLong("TimeModified",  System.currentTimeMillis());
        metadata.putInt("TotalBlocks",    nonAirCount);
        metadata.putInt("TotalVolume",    volume);
        metadata.putInt("RegionCount",    1);
        metadata.put("EnclosingSize",     enclosingSize);

        NbtCompound regions = new NbtCompound();
        regions.put("main", region);

        NbtCompound litematicNbt = new NbtCompound();
        litematicNbt.putInt("MinecraftDataVersion", dataVersion);
        litematicNbt.putInt("Version",              6);
        litematicNbt.putInt("SubVersion",           1);
        litematicNbt.put("Metadata",                metadata);
        litematicNbt.put("Regions",                 regions);

        NbtIo.writeCompressed(litematicNbt, outFile.toPath());
        LogUtils.log("Converted: " + nbtFile.getName() + " -> " + newName);
    }

    private <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value)
    {
        return property.parse(value)
            .map(v -> state.with(property, v))
            .orElse(state);
    }


    /***
     * init() handles all initial loading, such as files and bounds checks.
     */
    public void init()
    {
        schemFiles = schematicsFolder.listFiles((dir, name) -> name.endsWith(".litematic"));

        if (schemFiles == null || schemFiles.length == 0)
        {
            LogUtils.error("No schematics found in: " + schematicsFolder.getAbsolutePath());
            pause("No Schem Found");
            return;
        }

        // note: case-insensitive
        Arrays.sort(schemFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        if (schematicsIndex.get() >= schemFiles.length)
        {
            LogUtils.log("Schematic index: " + schematicsIndex.get() + " is out of range — all done!");
            state = BotState.FINISHED;
            return;
        }

        LogUtils.log("Found " + schemFiles.length + " schematics. Starting at index "
            + schematicsIndex.get() + " (" + schemFiles[schematicsIndex.get()].getName() + ")");

        state = BotState.ANALYZING;
    }

    /***
     * analyzeSchematic() loads and places the schematic and computes material requirements.
     */
    public void analyzeSchematic()
    {
        int index = schematicsIndex.get();

        if (schemFiles == null || index >= schemFiles.length)
        {
            state = BotState.FINISHED;
            return;
        }

        File schFile = schemFiles[index];

        LogUtils.log("Loading Schematic [ " + index + " ]: " + schFile.getName());
        LogUtils.log("File path: " + schFile.getAbsolutePath());
        LogUtils.log("File exists: " + schFile.exists() + " | Size: " + schFile.length() + " bytes");

        String nameNoExt = schFile.getName().replace(".litematic", "");
        currentSchematic = LitematicaSchematic.createFromFile(schematicsFolder, nameNoExt);

        if (currentSchematic == null)
        {
            LogUtils.error("Failed to load schematic: " + schFile.getName());
            pause("Schematic Load Failed");
            return;
        }

        currentPlacement = SchematicPlacement.createFor(currentSchematic,
            schemPos.get(),
            nameNoExt,
            true,
            true);

        // note:: this should always be false but we have it here just in case
        if (currentPlacement == null)
        {
            LogUtils.error("Failed to create placement for: " + schFile.getName());
            pause("Placement Creation Failed");
            return;
        }

        SchematicPlacementManager worldPlacement = DataManager.getSchematicPlacementManager();
        if (worldPlacement == null)
        {
            LogUtils.error("Litematica placement manager unavailable.");
            pause("Placement Manager Unavailable");
            return;
        }
        worldPlacement.addSchematicPlacement(currentPlacement, true);


        matNeeded.clear();
        totalBlocks = 0;
        MaterialListSchematic matList = new MaterialListSchematic(currentSchematic, true);
        for (MaterialListEntry entry : matList.getMaterialsAll())
        {
            matNeeded.put(entry.getStack().getItem(), entry.getCountTotal());
            totalBlocks += entry.getCountTotal();
        }

        LogUtils.log("Schematic loaded. Total blocks to place: " + totalBlocks);


        unplacedCache        = new ArrayList<>();
        remainingMatsCache   = new HashMap<>();
        unplacedCacheTimer.ms = 0;

        state = BotState.CHEST_SCAN;
    }

    /***
     * scanChests() walks the restock-station bounding box, maps each chest to a block by looking at its
     * neighbors, and ignores neighbors that are air, hoppers, other chests, or the platform indicator block.
     */
    public void scanChests()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.world == null)
        {
            LogUtils.error("Error: No Minecraft Instance Found");
            pause("No Minecraft Instance Found");
            return;
        }

        chestMap.clear();

        BlockPos minCorner = restockMin.get();
        BlockPos maxCorner = restockMax.get();
        Block    skipBlock = platformBlock.get();

        for (BlockPos pos : BlockPos.iterate(minCorner, maxCorner))
        {
            BlockState blockState = mc.world.getBlockState(pos);

            if (!(blockState.getBlock() instanceof ChestBlock)) continue;

            for (Direction dir : Direction.values())
            {
                BlockPos   neighborPos    = pos.offset(dir);
                BlockState neighbor       = mc.world.getBlockState(neighborPos);
                Block      indicatorBlock = neighbor.getBlock();

                if (indicatorBlock == Blocks.AIR)           continue;
                if (indicatorBlock == Blocks.HOPPER)        continue;
                if (indicatorBlock == skipBlock)            continue;
                if (indicatorBlock instanceof ChestBlock)   continue;

                BlockPos chestPosCopy = pos.toImmutable();

                chestMap.computeIfAbsent(indicatorBlock, k -> new ArrayList<>()).add(chestPosCopy);
                LogUtils.log("Mapped: " + indicatorBlock + " - chest at " + pos);
                break;
            }
        }

        if (chestMap.isEmpty())
        {
            LogUtils.error("The Chest Map is empty - No chest found in your bounding box");
            pause("No Chest Found");
            return;
        }

        LogUtils.log(chestMap.size() + " Chests Found And Mapped!");
        state = nextStateAfterDropOrRestock();
    }

    /***
     * doRestock() pulls the next needed item from the correct chest until the bot has enough to place.
     */
    private void doRestock()
    {
        int freeSlots = getFreeInventorySlots();

        if (freeSlots <= 0)
        {
            LogUtils.log("Inventory full — heading to place.");
            walkingToStation  = false;
            currentGrabItem   = null;
            currentChestIndex = 0;
            baritoneArrived   = false;
            state = BotState.PLACING;
            return;
        }

        if (getNextNeededEntry(freeSlots) == null)
        {
            LogUtils.log("All materials collected — moving to placing.");
            walkingToStation  = false;
            currentGrabItem   = null;
            currentChestIndex = 0;
            baritoneArrived   = false;
            state = BotState.PLACING;
            return;
        }

        grabItem();
    }

    /***
     * nextStateAfterDropOrRestock() returns DROPPING_EXCESS if the drop-excess feature is enabled and
     * there's actually something to drop, otherwise RESTOCKING. Used everywhere we'd normally jump
     * straight to RESTOCKING.
     */
    private BotState nextStateAfterDropOrRestock()
    {
        if (dropExcessEnabled.get() && findNextExcessSlot() != -1)
        {
            return BotState.DROPPING_EXCESS;
        }
        return BotState.RESTOCKING;
    }

    /***
     * doDropExcess() walks to the configured drop position and Q-drops every inventory stack whose
     * item is NOT required by the current schematic (and isn't on the protected-items keep-list).
     * Items are dropped one stack per tick-cycle, throttled by itemTakeDelay.
     */
    private void doDropExcess()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.interactionManager == null) return;

        if (!dropExcessEnabled.get() || findNextExcessSlot() == -1)
        {
            walkingToDropPos = false;
            baritoneArrived  = false;
            state = BotState.RESTOCKING;
            return;
        }

        BlockPos dropPos = dropExcessPos.get();

        if (!walkingToDropPos)
        {
            LogUtils.log("Walking to drop position " + dropPos);
            BaritoneUtils.goToNear(dropPos, 2);
            walkingToDropPos = true;
            baritoneArrived  = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(dropPos));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to drop position (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goToNear(dropPos, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at drop position.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassed((int)(baritoneSleepSeconds.get() * 1000))) return;

        // throttle drops between stacks so the server doesn't drop packets
        if (!itemGrabTimer.hasPassedDouble(itemTakeDelay.get() * 1000)) return;

        int dropSlot = findNextExcessSlot();

        if (dropSlot == -1)
        {
            LogUtils.log("Excess drop done — moving to restock.");
            walkingToDropPos = false;
            baritoneArrived  = false;
            state = BotState.RESTOCKING;
            return;
        }

        int screenSlot = invSlotToPlayerScreenSlot(dropSlot);
        if (screenSlot == -1)
        {
            LogUtils.error("Could not translate inv slot " + dropSlot + " to player-screen slot — skipping.");
            return;
        }

        ItemStack stack = mc.player.getInventory().getStack(dropSlot);
        Item      item  = stack.getItem();
        int       count = stack.getCount();

        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            screenSlot,
            1,
            SlotActionType.THROW,
            mc.player
        );

        LogUtils.log("Dropped " + count + "x " + item + " from inv slot " + dropSlot);
        itemGrabTimer.reset();
    }

    /***
     * findNextExcessSlot() returns the first inventory slot holding an excess item, or -1.
     *
     * Excess = an item whose block is no longer needed anywhere in the current schematic
     *
     * The first FOOD_SLOT_COUNT hotbar slots are reserved for food and are never touched.
     * Protected items (maps, glass panes, the food items themselves) are also never dropped.
     */
    private int findNextExcessSlot()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;

        Set<Block> stillNeeded = computeStillNeededBlocks();

        for (int i = FOOD_SLOT_COUNT; i <= 35; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (isProtectedItem(item)) continue;

            Block block = Block.getBlockFromItem(item);
            if (stillNeeded.contains(block)) continue;

            return i;
        }
        return -1;
    }

    /***
     * computeStillNeededBlocks() returns the set of block types that still appear in at least one
     * unplaced schematic position. Anything not in this set is dead weight in the inventory.
     */
    private Set<Block> computeStillNeededBlocks()
    {
        Set<Block> blocks = new HashSet<>();
        for (Item item : computeRemainingNeededMaterials().keySet())
        {
            Block block = Block.getBlockFromItem(item);
            if (block != Blocks.AIR) blocks.add(block);
        }
        return blocks;
    }

    /***
     * computeRemainingNeededMaterials() returns a map of item -> quantity-still-needed.
     *
     */
    private Map<Item, Integer> computeRemainingNeededMaterials()
    {
        if (currentSchematic == null || currentPlacement == null) return new HashMap<>();

        getUnplacedBlocks();
        return remainingMatsCache;
    }

    /***
     * isProtectedItem() returns true for items the bot must never drop — maps used by the mapping
     * flow and food it keeps in the hotbar.
     */
    private boolean isProtectedItem(Item item)
    {
        // NOTE ::: in the future we can just make this a setting
        return item == Items.MAP
            || item == Items.FILLED_MAP
            || item == Items.GLASS_PANE
            || item == Items.GOLDEN_APPLE
            || item == Items.ENCHANTED_GOLDEN_APPLE
            || item == Items.GOLDEN_CARROT
            || item == Items.BREAD;
    }

    /***
     * invSlotToPlayerScreenSlot() translates a PlayerInventory slot (0-35) to a slot id in the
     * player's own screen handler (PlayerScreenHandler), which is always live as
     * mc.player.playerScreenHandler even when no UI is open.
     *
     */
    private static int invSlotToPlayerScreenSlot(int invSlot)
    {
        if (invSlot < 0)   return -1;
        if (invSlot < 9)   return 36 + invSlot;
        if (invSlot <= 35) return invSlot;
        return -1;
    }

    /***
     * doPlacing() walks to the next placeable cluster and lets litematica Printer place blocks there.
     */
    public void doPlacing()
    {
        List<BlockPos> unplaced = getUnplacedBlocks();

        if (unplaced.isEmpty())
        {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
            LogUtils.log("All blocks placed — printer turned off. Moving to verification.");
            currentClusterTarget = null;
            BaritoneUtils.forceCancel();
            state = BotState.VERIFYING;
            return;
        }

        if (fixErrors.get() && !getWrongBlocks(unplaced).isEmpty())
        {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
            LogUtils.log("Wrong block(s) detected — switching to error clearing.");
            currentClusterTarget = null;
            BaritoneUtils.forceCancel();
            state = BotState.CLEARING_ERRORS;
            return;
        }

        if (needsRestock())
        {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
            LogUtils.log("Out of materials — printer off, heading to restock.");
            currentClusterTarget = null;
            BaritoneUtils.forceCancel();
            state = nextStateAfterDropOrRestock();
            return;
        }

        if (currentClusterTarget == null)
        {
            currentClusterTarget = getNextClusterCenter(unplaced);

            if (currentClusterTarget == null)
            {
                LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
                LogUtils.log("No placeable clusters found — heading to restock.");
                BaritoneUtils.forceCancel();
                clusterNearestToPlayer = false;
                state = nextStateAfterDropOrRestock();
                return;
            }

            LogUtils.log("walking to cluster at " + currentClusterTarget
                + " (" + unplaced.size() + " blocks remaining)");

            LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);
            BaritoneUtils.goTo(currentClusterTarget);
            baritoneStartTimer.reset();
            clusterArrivalTimer.reset();
            clusterStuckTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing())
        {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);
            return;
        }

        LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);

        if (!clusterArrivalTimer.hasPassedDouble(clusterCheckInterval.get() * 1000)) return;

        BlockPos newTarget = getNextClusterCenter(unplaced);

        if (newTarget == null)
        {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
            LogUtils.log("No placeable clusters remaining — heading to restock.");
            BaritoneUtils.forceCancel();
            currentClusterTarget   = null;
            clusterNearestToPlayer = false;
            state = nextStateAfterDropOrRestock();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        int playerCellX = Math.floorDiv(mc.player.getBlockPos().getX(), CLUSTER_SIZE);
        int playerCellZ = Math.floorDiv(mc.player.getBlockPos().getZ(), CLUSTER_SIZE);
        int newCellX    = Math.floorDiv(newTarget.getX(),               CLUSTER_SIZE);
        int newCellZ    = Math.floorDiv(newTarget.getZ(),               CLUSTER_SIZE);

        if (playerCellX != newCellX || playerCellZ != newCellZ)
        {
            LogUtils.log("Migrating to next cluster " + newTarget
                + " (" + unplaced.size() + " blocks remaining)");
            currentClusterTarget = newTarget;
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);
            BaritoneUtils.goTo(newTarget);
            baritoneStartTimer.reset();
            clusterStuckTimer.reset();
            clusterArrivalTimer.reset();
            return;
        }

        if (clusterStuckTimer.hasPassedDouble(clusterStuckTimeout.get() * 1000))
        {
            nudgePlayerToDislodge();
            return;
        }

        clusterArrivalTimer.reset();
    }

    /***
     * nudgePlayerToDislodge() sends Baritone to a small sideways offset from the current cluster
     * target,allowing for Baritone to never get stuck in pathing.
     */
    private void nudgePlayerToDislodge()
    {
        if (currentClusterTarget == null) return;

        int[][] dirs = { {3, 0, 0}, {-3, 0, 0}, {0, 0, 3}, {0, 0, -3} };
        int[]   d    = dirs[nudgeDirIdx % dirs.length];
        nudgeDirIdx++;

        BlockPos nudgeTarget = currentClusterTarget.add(d[0], d[1], d[2]);

        LogUtils.log("Stuck at cluster for "
            + String.format("%.1f", clusterStuckTimeout.get())
            + "s — nudging to " + nudgeTarget + " to free under-foot block.");

        LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);
        BaritoneUtils.goTo(nudgeTarget);
        baritoneStartTimer.reset();
        clusterArrivalTimer.reset();
        clusterStuckTimer.reset();
    }

    /***
     * getWrongBlocks() returns positions that are currently OCCUPIED by a block that doesn't match the
     * schematic (a "wrong" block the printer can't fix on its own), as opposed to "missing" positions
     * that are just air. The world and schematic states are both read live so a block the printer just
     * placed correctly is never mistaken for a wrong block.
     */
    private List<BlockPos> getWrongBlocks(List<BlockPos> unplaced)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<BlockPos> wrong = new ArrayList<>();
        if (mc == null || mc.world == null) return wrong;

        for (BlockPos pos : unplaced)
        {
            BlockState worldState = mc.world.getBlockState(pos);
            if (worldState.isAir()) continue;

            BlockState schemState = getSchemStateAt(pos);
            if (schemState == null || schemState.isAir()) continue;

            if (!worldState.equals(schemState)) wrong.add(pos);
        }
        return wrong;
    }

    /***
     * doClearErrors() walks to each wrong block (nearest first) and breaks it so the printer can
     * re-place the correct block. A per-block timeout moves stubborn blocks to a give-up list so a
     * single un-mineable block can't replace the old circle with a new one.
     */
    private void doClearErrors()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);

        List<BlockPos> wrong = getWrongBlocks(getUnplacedBlocks());

        if (wrong.isEmpty())
        {
            LogUtils.log("No wrong blocks remaining — back to placing.");
            currentErrorTarget = null;
            walkingToError     = false;
            baritoneArrived    = false;
            BaritoneUtils.forceCancel();
            state = BotState.PLACING;
            return;
        }

        if (currentErrorTarget == null || !wrong.contains(currentErrorTarget))
        {
            currentErrorTarget = nearestPos(wrong, mc.player.getPos());
            walkingToError     = false;
            baritoneArrived    = false;
        }

        if (currentErrorTarget == null) return;

        if (mc.world.getBlockState(currentErrorTarget).isAir())
        {
            currentErrorTarget = null;
            return;
        }

        if (!walkingToError)
        {
            LogUtils.log("Walking to wrong block at " + currentErrorTarget);
            BaritoneUtils.goToNear(currentErrorTarget, 2);
            walkingToError  = true;
            baritoneArrived = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(currentErrorTarget));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to wrong block ("
                    + String.format("%.1f", dist) + ") re-pathing.");
                BaritoneUtils.goToNear(currentErrorTarget, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at wrong block " + currentErrorTarget + " — breaking.");
            baritoneArrived = true;
            errorBreakTimer.reset();
            return;
        }

        if (errorBreakTimer.hasPassedDouble(errorBreakTimeout.get() * 1000))
        {
            LogUtils.error("Could not break wrong block at " + currentErrorTarget
                + " within " + errorBreakTimeout.get() + "s — skipping it.");
            unbreakableErrors.add(currentErrorTarget.toImmutable());
            currentErrorTarget = null;
            walkingToError     = false;
            baritoneArrived    = false;

            unplacedCacheTimer.ms = 0;
            return;
        }

        if (breakBlock(currentErrorTarget))
        {
            LogUtils.log("Broke wrong block at " + currentErrorTarget + ".");
            currentErrorTarget = null;
            walkingToError     = false;
            baritoneArrived    = false;
            unplacedCacheTimer.ms = 0;
        }
    }

    /***
     * nearestPos() returns the position in the list closest to from, or null if the list is empty.
     */
    private BlockPos nearestPos(List<BlockPos> positions, Vec3d from)
    {
        BlockPos best     = null;
        double   bestDist = Double.MAX_VALUE;
        for (BlockPos pos : positions)
        {
            double d = from.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < bestDist)
            {
                bestDist = d;
                best     = pos;
            }
        }
        return best;
    }

    /***
     * breakBlock() rotates the player to face the block and mines it. Returns true once the block is
     * air (instantly in creative, after enough ticks in survival).
     */
    private boolean breakBlock(BlockPos pos)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return true;

        if (mc.world.getBlockState(pos).isAir()) return true;

        lookAt(Vec3d.ofCenter(pos));
        mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);

        return mc.world.getBlockState(pos).isAir();
    }

    /***
     * lookAt() points the player's view at a world position. Servers commonly range/line-of-sight
     * check block interactions, so facing the target before interacting/mining makes it reliable.
     */
    private void lookAt(Vec3d target)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        Vec3d  eye   = mc.player.getEyePos();
        double dx    = target.x - eye.x;
        double dy    = target.y - eye.y;
        double dz    = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    /***
     * interactBlockLooking() faces the block, then right-clicks it. Use for opening chests / tables /
     * pressing buttons so the interaction isn't rejected for not looking at the target.
     */
    private void interactBlockLooking(BlockPos pos, Direction face)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.interactionManager == null) return;

        lookAt(Vec3d.ofCenter(pos));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), face, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
    }

    /***
     * doVerify() rescans the world against the schematic to make sure no blocks were missed.
     */
    private void doVerify()
    {
        List<BlockPos> mismatch = getUnplacedBlocks();

        if (mismatch.isEmpty())
        {
            LogUtils.log("The Schematic Is Filled, Moving To Mapping :D");
            unplacedCache = new ArrayList<>();
            state = BotState.CAPTURE_MAP;
        }
        else
        {
            LogUtils.log(mismatch.size() + " Mismatches, The Schematic is not finished - continue placing");
            state = BotState.PLACING;
        }
    }

    /***
     * doCaptureMap() grabs a map + glass pane, walks to the platform center, and renders the map.
     */
    private void doCaptureMap()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.interactionManager == null) return;

        BlockPos mapChest = mapChestPos.get();

        if (!walkingToMapChest)
        {
            LogUtils.log("Walking to map chest at " + mapChest);
            BaritoneUtils.goToNear(mapChest, 2);
            walkingToMapChest = true;
            baritoneArrived   = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(mapChest));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to map chest (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goToNear(mapChest, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at map chest.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassed((int)(baritoneSleepSeconds.get() * 1000))) return;

        if (!mapGrabed)
        {
            grabOneMap(mapChest);
            return;
        }

        if (!glassPaneGrabbed)
        {
            grabOneGlassPane(mapChest);
            return;
        }

        BlockPos schemCenter = new BlockPos(
            currentPlacement.getOrigin().getX() + SCHEMATIC_CENTER,
            currentPlacement.getOrigin().getY() + 1,
            currentPlacement.getOrigin().getZ() + SCHEMATIC_CENTER
        );

        if (!walkingToMapCenter)
        {
            LogUtils.log("Walking to platform center at " + schemCenter);
            BaritoneUtils.goTo(schemCenter);
            walkingToMapCenter = true;
            baritoneArrived    = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(schemCenter));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to platform center (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goTo(schemCenter);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at platform center.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassed((int)(baritoneSleepSeconds.get() * 1000))) return;

        if (!mapRightClicked)
        {
            if (!isEquipEmptyMap())
            {
                LogUtils.error("Map not in hotbar — did grabOneMap() succeed?");
                pause("Missing Map In Hotbar");
                return;
            }

            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            LogUtils.log("Map right-clicked — waiting " + mapRenderTime.get() + "s for render...");
            mapRightClicked = true;
            mapRenderTimer.reset();
            return;
        }

        if (!mapRenderTimer.hasPassedDouble(mapRenderTime.get() * 1000))
        {
            if (progressLogTimer.hasPassed(1000))
            {
                LogUtils.log("Waiting for map render...");
                progressLogTimer.reset();
            }
            return;
        }

        LogUtils.log("Map captured! Moving to lock map.");
        walkingToMapChest  = false;
        walkingToMapCenter = false;
        mapGrabed          = false;
        glassPaneGrabbed   = false;
        mapRightClicked    = false;
        baritoneArrived    = false;
        state = BotState.LOCK_MAP;
    }

    /***
     * depositMap() drops the locked map into the output chest.
     */
    private void depositMap()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        BlockPos outputChest = outputChestPos.get();

        if (!walkingToOutputChest)
        {
            LogUtils.log("Walking to output chest at " + outputChest);
            BaritoneUtils.goToNear(outputChest, 2);
            walkingToOutputChest = true;
            baritoneArrived      = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(outputChest));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to output chest (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goToNear(outputChest, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at output chest.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassedDouble(baritoneSleepSeconds.get() * 1000)) return;

        ScreenHandler chestHandler = mc.player.currentScreenHandler;

        if (!(chestHandler instanceof GenericContainerScreenHandler chest))
        {
            interactBlockLooking(outputChest, Direction.UP);
            return;
        }

        if (!itemGrabTimer.hasPassedDouble(itemTakeDelay.get() * 1000)) return;

        int mapInvSlot = findItemInInv(Items.FILLED_MAP);

        if (mapInvSlot == -1)
        {
            LogUtils.error("Could not find filled map in inventory");
            mc.player.closeHandledScreen();
            pause("No Map In Inventory");
            return;
        }

        int chestSize     = chest.getRows() * 9;
        int mapScreenSlot = playerSlotToScreenSlot(mapInvSlot, chestSize);

        boolean chestHasRoom = false;
        for (int i = 0; i < chestSize; i++)
        {
            if (chest.getSlot(i).getStack().isEmpty()) { chestHasRoom = true; break; }
        }

        if (!chestHasRoom)
        {
            LogUtils.error("Output chest is full — no empty slots!");
            mc.player.closeHandledScreen();
            pause("Output Chest Full");
            return;
        }

        if (mapScreenSlot == -1)
        {
            LogUtils.error("Could not translate inv slot " + mapInvSlot + " to screen slot.");
            mc.player.closeHandledScreen();
            pause("Slot Translation Failed");
            return;
        }

        mc.interactionManager.clickSlot(
            chest.syncId,
            mapScreenSlot,
            0,
            SlotActionType.QUICK_MOVE,
            mc.player
        );

        itemGrabTimer.reset();
        LogUtils.log("Deposited locked map into output chest.");
        mc.player.closeHandledScreen();
        walkingToOutputChest = false;
        baritoneArrived      = false;
        state = BotState.RESETTING_PLATFORM;
    }

    /***
     * lockMapProc() places the filled map + glass pane in a cartography table and pulls the locked map.
     */
    private void lockMapProc()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        BlockPos cartTable = cartTablePos.get();

        if (!walkingToCartographyTable)
        {
            LogUtils.log("Walking to cartography table at: " + cartTable);
            BaritoneUtils.goToNear(cartTable, 2);
            walkingToCartographyTable = true;
            baritoneArrived           = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(cartTable));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to cartography table (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goToNear(cartTable, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at cartography table.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassedDouble(baritoneSleepSeconds.get() * 1000)) return;

        ScreenHandler handler = mc.player.currentScreenHandler;

        if (!(handler instanceof CartographyTableScreenHandler tableScreen))
        {
            interactBlockLooking(cartTable, Direction.UP);
            return;
        }

        if (!mapPlacedInTable)
        {
            int mapInvSlot    = findItemInInv(Items.FILLED_MAP);
            int mapScreenSlot = playerSlotToScreenSlot(mapInvSlot, 3);

            if (mapInvSlot == -1 || mapScreenSlot == -1)
            {
                LogUtils.error("No Filled Map In Inventory");
                mc.player.closeHandledScreen();
                pause("No Filled Map In Players Inventory");
                return;
            }

            mc.interactionManager.clickSlot(tableScreen.syncId, mapScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(tableScreen.syncId, 0, 0, SlotActionType.PICKUP, mc.player);

            mapPlacedInTable = true;
            itemGrabTimer.reset();
            return;
        }

        if (!itemGrabTimer.hasPassedDouble(itemTakeDelay.get() * 1000)) return;

        if (!glassPanePlacedInTable)
        {
            int glassInvSlot    = findItemInInv(Items.GLASS_PANE);
            int glassScreenSlot = playerSlotToScreenSlot(glassInvSlot, 3);

            if (glassInvSlot == -1 || glassScreenSlot == -1)
            {
                LogUtils.error("No Glass Pane In Inventory");
                mc.player.closeHandledScreen();
                pause("No Glass Pane In Players Inventory");
                return;
            }

            mc.interactionManager.clickSlot(tableScreen.syncId, glassScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(tableScreen.syncId, 1, 0, SlotActionType.PICKUP, mc.player);

            glassPanePlacedInTable = true;
            itemGrabTimer.reset();
            return;
        }

        if (!itemGrabTimer.hasPassedDouble(itemTakeDelay.get() * 1000)) return;

        if (!mapLocked)
        {
            ItemStack result = tableScreen.getSlot(2).getStack();

            if (result.isEmpty())
            {
                LogUtils.log("Waiting for cartography table result...");
                return;
            }

            mc.interactionManager.clickSlot(tableScreen.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);

            LogUtils.log("Map Locked Successfully!");
            mapLocked = true;
            itemGrabTimer.reset();
            return;
        }

        mc.player.closeHandledScreen();
        LogUtils.log("Map locked — moving to deposit.");
        walkingToCartographyTable = false;
        mapPlacedInTable          = false;
        glassPanePlacedInTable    = false;
        mapLocked                 = false;
        baritoneArrived           = false;
        state = BotState.MOVE_TO_OUTPUT;
    }

    /***
     * doReset() resets a carpet platform by pressing a button at a configured position, then advances
     * to the next schematic.
     */
    private void doReset()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        BlockPos restButton = resetButtonPos.get();

        if (!walkingToRestButton)
        {
            LogUtils.log("Walking to reset button @ " + restButton);
            BaritoneUtils.goToNear(restButton, 2);
            walkingToRestButton = true;
            baritoneArrived     = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(restButton));
            if (dist > 4.0)
            {
                LogUtils.log("Not close enough to reset button (" + String.format("%.1f", dist) + ") — re-pathing.");
                BaritoneUtils.goToNear(restButton, 2);
                baritoneStartTimer.reset();
                return;
            }
            LogUtils.log("Arrived at reset button.");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassed((int)(baritoneSleepSeconds.get() * 1000))) return;

        if (!resetButtonPressed)
        {
            BlockState buttonState = mc.world.getBlockState(restButton);

            Direction buttonFace;

            if (buttonState.getProperties().contains(FacingBlock.FACING))
            {
                buttonFace = buttonState.get(FacingBlock.FACING);
            }
            else
            {
                buttonFace = Direction.UP;
            }

            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(restButton),
                buttonFace,
                restButton,
                false
            );

            lookAt(Vec3d.ofCenter(restButton));
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            LogUtils.log("Reset Button Pressed at " + restButton);
            resetWaitTimer.reset();
            resetButtonPressed = true;
            return;
        }

        if (!resetWaitTimer.hasPassedDouble(acceptedResetWait.get() * 1000)) return;

        advanceToNextSchematic();
    }

    /***
     * doResetBreak() resets a full-block platform by asking Baritone to mine every block inside the
     * schematic bounding box, then advances to the next schematic.
     */
    private void doResetBreak()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) return;

        if (currentSchematic == null || currentPlacement == null)
        {
            LogUtils.error("doResetBreak called without an active schematic.");
            advanceToNextSchematic();
            return;
        }

        BlockPos[] bounds = computeSchematicBounds();
        if (bounds == null)
        {
            LogUtils.error("Could not compute schematic bounds for clearing — skipping.");
            advanceToNextSchematic();
            return;
        }

        if (!clearAreaStarted)
        {
            LogUtils.log("Clearing platform " + bounds[0] + " → " + bounds[1] + " via Baritone clearArea.");
            BaritoneAPI.getProvider().getPrimaryBaritone()
                .getBuilderProcess().clearArea(bounds[0], bounds[1]);
            clearAreaStarted = true;
            clearAreaTimer.reset();
            progressLogTimer.reset();
            return;
        }

        if (clearAreaTimer.hasPassedDouble(clearAreaMaxSeconds.get() * 1000L))
        {
            LogUtils.error("Clear-area timed out after " + clearAreaMaxSeconds.get()
                + "s — cancelling and moving on.");
            BaritoneUtils.forceCancel();
            advanceToNextSchematic();
            return;
        }

        if (BaritoneUtils.isPathing() || !isAreaCleared(bounds[0], bounds[1]))
        {
            if (progressLogTimer.hasPassed(5000))
            {
                LogUtils.log("Still clearing platform...");
                progressLogTimer.reset();
            }
            return;
        }

        LogUtils.log("Platform cleared!");
        BaritoneUtils.forceCancel();
        advanceToNextSchematic();
    }

    /***
     * advanceToNextSchematic() resets per-schematic state, removes the previous placement from
     * Litematica's renderer
     */
    private void advanceToNextSchematic()
    {
        removeCurrentPlacement();
        resetAllFlags();

        int nextIndex = schematicsIndex.get() + 1;

        if (nextIndex >= schemFiles.length)
        {
            LogUtils.log("All schematics complete!");
            state = BotState.FINISHED;
            return;
        }

        schematicsIndex.set(nextIndex);
        currentSchematic = null;
        currentPlacement = null;
        unplacedCache    = new ArrayList<>();
        unbreakableErrors.clear();

        LogUtils.log("Moving to Schematic #" + nextIndex + ": " + schemFiles[nextIndex].getName());
        state = BotState.ANALYZING;
    }

    /***
     * removeCurrentPlacement() unregisters the active placement from Litematica's SchematicPlacement
     * Manager so the renderer drops it. Safe to call when there's no active placement.
     */
    private void removeCurrentPlacement()
    {
        if (currentPlacement == null) return;

        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        if (mgr != null)
        {
            mgr.removeSchematicPlacement(currentPlacement);
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event)
    {
        if (MinecraftClient.getInstance().player == null) return;

        switch (state)
        {
            case IDLE                -> convertSchematics();
            case CONVERTING          -> convertSchematics();
            case INITIALIZING        -> init();
            case ANALYZING           -> analyzeSchematic();
            case CHEST_SCAN          -> scanChests();
            case DROPPING_EXCESS     -> doDropExcess();
            case RESTOCKING          -> doRestock();
            case PLACING             -> doPlacing();
            case CLEARING_ERRORS     -> doClearErrors();
            case VERIFYING           -> doVerify();
            case CAPTURE_MAP         -> doCaptureMap();
            case LOCK_MAP            -> lockMapProc();
            case MOVE_TO_OUTPUT      -> depositMap();
            case RESETTING_PLATFORM  ->
            {
                // full block reset otherwise carpet reset
                if (fullBlock.get()) doResetBreak();
                else                 doReset();
            }
            case PAUSED              -> { }
            case FINISHED            ->
            {
                LogUtils.log("All schematics finished!");
                toggle();
            }
        }
    }

    @Override
    public void onActivate()
    {
        resetAllFlags();

        conversionFuture = null;
        state = BotState.IDLE;
        LogUtils.log("FluxPrint activated.");
    }

    @Override
    public void onDeactivate()
    {
        BaritoneUtils.forceCancel();
        LitematicaMixinMod.PRINT_MODE.setBooleanValue(false);
        removeCurrentPlacement();
        resetAllFlags();
        currentSchematic = null;
        currentPlacement = null;
        LogUtils.log("FluxPrint deactivated.");
    }

    private void pause(String reason)
    {
        LogUtils.error("Bot Paused - Reason: " + reason);
        state = BotState.PAUSED;
    }

    /***
     * resetAllFlags() resets every sub-state boolean and per-schematic counter so a new run starts clean.
     */
    private void resetAllFlags()
    {
        walkingToStation          = false;
        walkingToMapCenter        = false;
        walkingToMapChest         = false;
        resetButtonPressed        = false;
        walkingToRestButton       = false;
        mapGrabed                 = false;
        glassPaneGrabbed          = false;
        mapRightClicked           = false;
        walkingToCartographyTable = false;
        mapPlacedInTable          = false;
        glassPanePlacedInTable    = false;
        mapLocked                 = false;
        walkingToOutputChest      = false;
        baritoneArrived           = false;
        clusterNearestToPlayer    = false;
        clearAreaStarted          = false;
        walkingToDropPos          = false;
        walkingToError            = false;
        tookFirstSlot             = false;
        currentGrabItem           = null;
        currentChestIndex         = 0;
        currentClusterTarget      = null;
        currentErrorTarget        = null;
        nudgeDirIdx               = 0;
        unbreakableErrors.clear();
    }

    /***
     * playerSlotToScreenSlot() translates a PlayerInventory slot index (0-35) into a screen-handler
     * slot id, given how many "extra" (non-player) slots the screen has at the start.
     *
     * Minecraft screen layouts always place the player's main inventory right after the extra slots,
     * with the hotbar after that. This translation matches that convention.
     *
     * @param playerSlot 0-8 = hotbar, 9-35 = main inventory
     * @param extraSlots number of non-player slots at the start of the screen (e.g. 3 for cartography
     *                   table, chestRows*9 for a chest)
     * @return screen-handler slot id, or -1 if playerSlot is out of range
     */
    private static int playerSlotToScreenSlot(int playerSlot, int extraSlots)
    {
        if (playerSlot < 0)   return -1;
        if (playerSlot < 9)   return extraSlots + 27 + playerSlot;
        if (playerSlot <= 35) return extraSlots + (playerSlot - 9);
        return -1;
    }

    /***
     * findItemInInv() returns the player-inventory slot of the first stack matching item, or -1.
     */
    private int findItemInInv(Item item)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;

        for (int i = 0; i < mc.player.getInventory().size(); i++)
        {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    /***
     * isEquipEmptyMap() selects an empty map in the hotbar if one is present.
     *
     * @return true if a map was found and equipped, false otherwise
     */
    public boolean isEquipEmptyMap()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        for (int i = 0; i < 9; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.MAP)
            {
                mc.player.getInventory().setSelectedSlot(i);
                LogUtils.log("Equipped empty map in hotbar slot " + i);
                return true;
            }
        }

        LogUtils.error("Empty map not in hotbar — did grabOneMap() succeed?");
        return false;
    }

    /***
     * getNextNeededItem() returns the first item from matNeeded the player doesn't yet have enough of.
     */
    private Item getNextNeededItem()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return null;

        for (Map.Entry<Item, Integer> entry : matNeeded.entrySet())
        {
            Item item   = entry.getKey();
            int  needed = entry.getValue();
            int  have   = countItemInInventory(item);

            if (have < needed) return item;
        }
        return null;
    }

    /***
     * getUnplacedBlocks() walks every region of the active schematic and returns world positions
     * whose world block state doesn't match the schematic. The scan ALSO populates
     * remainingMatsCache (item -> count-still-needed) in the same pass, so anything that needs
     * the materials breakdown gets it for free without re-iterating.
     *
     */
    private List<BlockPos> getUnplacedBlocks()
    {
        if (!unplacedCacheTimer.hasPassedDouble(UNPLACED_CACHE_MS))
        {
            return unplacedCache;
        }

        if (currentPlacement == null)
        {
            unplacedCache      = new ArrayList<>();
            remainingMatsCache = new HashMap<>();
            return unplacedCache;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null || mc.world == null)
        {
            return unplacedCache;
        }

        List<BlockPos>     unplacedBlocks = new ArrayList<>();
        Map<Item, Integer> remainingMats  = new HashMap<>();

        for (Map.Entry<String, BlockPos> entry : currentSchematic.getAreaPositions().entrySet())
        {
            String   regionName   = entry.getKey();
            BlockPos regionOrigin = entry.getValue();

            LitematicaBlockStateContainer container = currentSchematic.getSubRegionContainer(regionName);

            if (container == null)
            {
                LogUtils.error("Region " + regionName + " container is null");
                continue;
            }

            Vec3i size = container.getSize();

            for (int x = 0; x < size.getX(); x++)
            {
                for (int y = 0; y < size.getY(); y++)
                {
                    for (int z = 0; z < size.getZ(); z++)
                    {
                        BlockState schemState = container.get(x, y, z);

                        if (schemState.isAir()) continue;

                        BlockPos worldPos = new BlockPos(
                            currentPlacement.getOrigin().getX() + regionOrigin.getX() + x,
                            currentPlacement.getOrigin().getY() + regionOrigin.getY() + y,
                            currentPlacement.getOrigin().getZ() + regionOrigin.getZ() + z);

                        BlockState worldState = mc.world.getBlockState(worldPos);

                        if (!worldState.equals(schemState))
                        {
                            if (unbreakableErrors.contains(worldPos)) continue;

                            unplacedBlocks.add(worldPos);

                            Item item = schemState.getBlock().asItem();
                            if (item != null && item != Items.AIR)
                            {
                                remainingMats.merge(item, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        unplacedCache      = unplacedBlocks;
        remainingMatsCache = remainingMats;
        unplacedCacheTimer.reset();

        LogUtils.log("Cache updated - " + unplacedBlocks.size() + " blocks remaining.");
        return unplacedCache;
    }

    /***
     * getNextClusterCenter() groups unplaced positions into CLUSTER_SIZE x CLUSTER_SIZE cells. On the
     * first call for a schematic it returns the cluster furthest from the restock station so placing
     * works from the back of the platform inward; subsequent calls return the cluster nearest to the
     * player to minimise travel.
     */
    private BlockPos getNextClusterCenter(List<BlockPos> unplaced)
    {
        if (unplaced.isEmpty()) return null;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return null;

        Vec3d playerPos = mc.player.getPos();

        Set<Block> blocksInInventory = new HashSet<>();
        for (Map.Entry<Item, Integer> entry : matNeeded.entrySet())
        {
            if (countItemInInventory(entry.getKey()) > 0)
            {
                blocksInInventory.add(Block.getBlockFromItem(entry.getKey()));
            }
        }

        List<BlockPos> placeable = new ArrayList<>();
        for (BlockPos pos : unplaced)
        {
            BlockState schemState = getSchemStateAt(pos);
            if (schemState != null && blocksInInventory.contains(schemState.getBlock()))
            {
                placeable.add(pos);
            }
        }

        if (placeable.isEmpty()) return null;

        Map<String, List<BlockPos>> clusters = new HashMap<>();
        for (BlockPos pos : placeable)
        {
            int cellX = Math.floorDiv(pos.getX(), CLUSTER_SIZE);
            int cellZ = Math.floorDiv(pos.getZ(), CLUSTER_SIZE);
            clusters.computeIfAbsent(cellX + "," + cellZ, k -> new ArrayList<>()).add(pos);
        }

        return selectCluster(clusters, playerPos);
    }

    /***
     * selectCluster() chooses which cluster to head for next according to the configured placement
     * algorithm. The doPlacing() migration logic decides when to actually move on, so this just needs
     * to return the "best next" cluster for the current ordering.
     */
    private BlockPos selectCluster(Map<String, List<BlockPos>> clusters, Vec3d playerPos)
    {
        switch (placementAlgo.get())
        {
            case NEAREST_FIRST ->
            {
                return nearestClusterToPlayer(clusters, playerPos);
            }

            case SCANLINE ->
            {
                return clusters.values().stream()
                    .map(this::averageClusterPos)
                    .min(Comparator.comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX))
                    .orElse(null);
            }

            case SPIRAL ->
            {
                BlockPos center = new BlockPos(
                    schemPos.get().getX() + SCHEMATIC_CENTER,
                    schemPos.get().getY(),
                    schemPos.get().getZ() + SCHEMATIC_CENTER);

                return clusters.values().stream()
                    .map(this::averageClusterPos)
                    .min(Comparator.comparingDouble(p -> MathUtils.getSquaredDistance(
                        p.getX(), center.getY(), p.getZ(),
                        center.getX(), center.getY(), center.getZ())))
                    .orElse(null);
            }

            default -> // FURTHEST_FIRST
            {
                if (!clusterNearestToPlayer)
                {

                    clusterNearestToPlayer = true;
                    BlockPos restockCorner = restockMin.get();
                    return clusters.values().stream()
                        .max(Comparator.comparingDouble(cluster ->
                        {
                            double avgX = cluster.stream().mapToInt(BlockPos::getX).average().orElse(0);
                            double avgZ = cluster.stream().mapToInt(BlockPos::getZ).average().orElse(0);
                            return MathUtils.getSquaredDistance(avgX, schemPos.get().getY(), avgZ,
                                restockCorner.getX(), restockCorner.getY(), restockCorner.getZ());
                        }))
                        .map(this::averageClusterPos)
                        .orElse(null);
                }

                return nearestClusterToPlayer(clusters, playerPos);
            }
        }
    }

    /***
     * nearestClusterToPlayer() returns the center of the cluster whose average position is closest to
     * the player, minimising travel.
     */
    private BlockPos nearestClusterToPlayer(Map<String, List<BlockPos>> clusters, Vec3d playerPos)
    {
        return clusters.values().stream()
            .min(Comparator.comparingDouble(cluster ->
            {
                double avgX = cluster.stream().mapToInt(BlockPos::getX).average().orElse(0);
                double avgZ = cluster.stream().mapToInt(BlockPos::getZ).average().orElse(0);
                return playerPos.squaredDistanceTo(avgX, playerPos.y, avgZ);
            }))
            .map(this::averageClusterPos)
            .orElse(null);
    }

    private BlockPos averageClusterPos(List<BlockPos> cluster)
    {
        int avgX = (int) cluster.stream().mapToInt(BlockPos::getX).average().orElse(0);
        int avgY = (int) cluster.stream().mapToInt(BlockPos::getY).average().orElse(0);
        int avgZ = (int) cluster.stream().mapToInt(BlockPos::getZ).average().orElse(0);
        return new BlockPos(avgX, avgY, avgZ);
    }

    /***
     * needsRestock() returns true when the player has none of any STILL-NEEDED material in their
     * inventory. Materials whose schematic positions are all already placed don't count — having
     * leftover dark-grey when no dark-grey positions remain doesn't postpone the restock trip.
     */
    private boolean needsRestock()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        Map<Item, Integer> remaining = computeRemainingNeededMaterials();

        if (remaining.isEmpty()) return false;

        for (Item item : remaining.keySet())
        {
            if (countItemInInventory(item) > 0) return false;
        }
        return true;
    }

    /***
     * getSchemStateAt() returns the schematic's block state at a given world position, or null if the
     * position is outside the schematic.
     */
    private BlockState getSchemStateAt(BlockPos worldPos)
    {
        if (currentSchematic == null || currentPlacement == null) return null;

        for (Map.Entry<String, BlockPos> entry : currentSchematic.getAreaPositions().entrySet())
        {
            String   regionName   = entry.getKey();
            BlockPos regionOrigin = entry.getValue();

            LitematicaBlockStateContainer container = currentSchematic.getSubRegionContainer(regionName);
            if (container == null) continue;

            Vec3i size = container.getSize();

            int localX = worldPos.getX() - currentPlacement.getOrigin().getX() - regionOrigin.getX();
            int localY = worldPos.getY() - currentPlacement.getOrigin().getY() - regionOrigin.getY();
            int localZ = worldPos.getZ() - currentPlacement.getOrigin().getZ() - regionOrigin.getZ();

            if (localX < 0 || localY < 0 || localZ < 0) continue;
            if (localX >= size.getX() || localY >= size.getY() || localZ >= size.getZ()) continue;

            return container.get(localX, localY, localZ);
        }
        return null;
    }

    /***
     * computeSchematicBounds() returns the world-space min/max corners enclosing every region of the
     * active schematic, or null if no schematic is loaded.
     */
    private BlockPos[] computeSchematicBounds()
    {
        if (currentSchematic == null || currentPlacement == null) return null;

        BlockPos placementOrigin = currentPlacement.getOrigin();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Map.Entry<String, BlockPos> e : currentSchematic.getAreaPositions().entrySet())
        {
            LitematicaBlockStateContainer container = currentSchematic.getSubRegionContainer(e.getKey());
            if (container == null) continue;

            BlockPos regionOrigin = e.getValue();
            Vec3i    size         = container.getSize();

            int rx0 = placementOrigin.getX() + regionOrigin.getX();
            int ry0 = placementOrigin.getY() + regionOrigin.getY();
            int rz0 = placementOrigin.getZ() + regionOrigin.getZ();
            int rx1 = rx0 + size.getX() - 1;
            int ry1 = ry0 + size.getY() - 1;
            int rz1 = rz0 + size.getZ() - 1;

            minX = Math.min(minX, rx0); minY = Math.min(minY, ry0); minZ = Math.min(minZ, rz0);
            maxX = Math.max(maxX, rx1); maxY = Math.max(maxY, ry1); maxZ = Math.max(maxZ, rz1);
        }

        if (minX == Integer.MAX_VALUE) return null;
        return new BlockPos[] { new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ) };
    }

    /***
     * isAreaCleared() returns true when every block inside the bounding box is air.
     */
    private boolean isAreaCleared(BlockPos min, BlockPos max)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;

        for (BlockPos p : BlockPos.iterate(min, max))
        {
            if (!mc.world.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    /***
     * grabItem() walks to a chest for the currently-needed item and pulls stacks until the requirement
     * is satisfied or the chest is empty.
     */
    private void grabItem()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        if (currentGrabItem == null)
        {
            Map.Entry<Item, Integer> entry = getNextNeededEntry(getFreeInventorySlots() - 1);
            if (entry == null)
            {
                state = BotState.PLACING;
                return;
            }

            currentGrabItem   = entry.getKey();
            currentChestIndex = 0;
        }

        int needed = computeRemainingNeededMaterials().getOrDefault(currentGrabItem, 0);
        int have   = countItemInInventory(currentGrabItem);

        if (have >= needed)
        {
            LogUtils.log("Have enough " + currentGrabItem
                + " (" + have + "/" + needed + ") — moving to next item.");
            currentGrabItem   = null;
            currentChestIndex = 0;
            walkingToStation  = false;
            baritoneArrived   = false;
            return;
        }

        Block          indicator = Block.getBlockFromItem(currentGrabItem);
        List<BlockPos> chests    = chestMap.get(indicator);
        if (chests == null || chests.isEmpty())
        {
            LogUtils.error("No Chest Mapped for: " + currentGrabItem);
            pause("Missing Chest Mapping");
            return;
        }

        if (currentChestIndex >= chests.size())
        {
            LogUtils.log("All chests exhausted for " + currentGrabItem
                + " — have " + have + "/" + needed + " — skipping to next item.");
            currentGrabItem   = null;
            currentChestIndex = 0;
            walkingToStation  = false;
            baritoneArrived   = false;
            return;
        }

        BlockPos chestPos = chests.get(currentChestIndex);

        if (!walkingToStation)
        {
            LogUtils.log("Walking to chest for: " + currentGrabItem + " at " + chestPos);
            BaritoneUtils.goToNear(chestPos, 2);
            walkingToStation = true;
            baritoneArrived  = false;
            tookFirstSlot    = false;
            baritoneStartTimer.reset();
            return;
        }

        if (!baritoneStartTimer.hasPassed(250)) return;
        if (BaritoneUtils.isPathing()) return;

        if (!baritoneArrived)
        {
            double distToChest = mc.player.getPos().distanceTo(Vec3d.ofCenter(chestPos));

            if (distToChest > 4.0)
            {
                LogUtils.log("Not close enough to chest ("
                    + String.format("%.1f", distToChest) + " blocks) — re-pathing.");
                BaritoneUtils.goToNear(chestPos, 3);
                baritoneStartTimer.reset();
                return;
            }

            LogUtils.log("Arrived at chest for: " + currentGrabItem
                + " (distance: " + String.format("%.1f", distToChest) + ")");
            baritoneArrivalTimer.reset();
            baritoneArrived = true;
            return;
        }

        if (!baritoneArrivalTimer.hasPassed((int)(baritoneSleepSeconds.get() * 1000))) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler chestScreen))
        {
            interactBlockLooking(chestPos, Direction.UP);
            return;
        }

        if (!itemGrabTimer.hasPassed((int)(itemTakeDelay.get() * 1000))) return;

        int chestSize = chestScreen.getRows() * 9;

        if (!tookFirstSlot)
        {
            tookFirstSlot = true;
            ItemStack slot0 = chestScreen.getSlot(0).getStack();

            if (!slot0.isEmpty() && slot0.getItem() == currentGrabItem)
            {
                if (getFreeInventorySlots() <= 1)
                {
                    LogUtils.log("Inventory full mid-grab (keeping 1 slot for food) — closing chest.");
                    mc.player.closeHandledScreen();
                    currentGrabItem   = null;
                    currentChestIndex = 0;
                    walkingToStation  = false;
                    baritoneArrived   = false;
                    state = BotState.PLACING;
                    return;
                }

                LogUtils.log("Taking slot 0 — have " + countItemInInventory(currentGrabItem) + " / need " + needed);
                mc.interactionManager.clickSlot(chestScreen.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                itemGrabTimer.reset();

                if (countItemInInventory(currentGrabItem) >= needed)
                {
                    LogUtils.log("Reached needed amount — closing chest.");
                    mc.player.closeHandledScreen();
                    currentGrabItem   = null;
                    currentChestIndex = 0;
                    walkingToStation  = false;
                    baritoneArrived   = false;
                }
                return;
            }
        }

        for (int slot = 1; slot < chestSize; slot++)
        {
            ItemStack stack = chestScreen.getSlot(slot).getStack();

            if (stack.isEmpty()) continue;
            if (stack.getItem() != currentGrabItem) continue;

            if (countItemInInventory(currentGrabItem) >= needed)
            {
                LogUtils.log("Reached needed amount — closing chest.");
                mc.player.closeHandledScreen();
                currentGrabItem   = null;
                currentChestIndex = 0;
                walkingToStation  = false;
                baritoneArrived   = false;
                return;
            }

            if (getFreeInventorySlots() <= 1)
            {
                LogUtils.log("Inventory full mid-grab (keeping 1 slot for food) — closing chest.");
                mc.player.closeHandledScreen();
                currentGrabItem   = null;
                currentChestIndex = 0;
                walkingToStation  = false;
                baritoneArrived   = false;
                state = BotState.PLACING;
                return;
            }

            mc.interactionManager.clickSlot(chestScreen.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            itemGrabTimer.reset();
            return;
        }

        LogUtils.log("Chest at " + chestPos + " empty for " + currentGrabItem + " — moving to next chest.");
        mc.player.closeHandledScreen();
        currentChestIndex++;
        walkingToStation = false;
        baritoneArrived  = false;
        baritoneStartTimer.reset();
    }

    /***
     * grabOneMap() opens the map chest (if not already open) and pulls a single empty map into the
     * hotbar.
     */
    private void grabOneMap(BlockPos chestPos)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        if (countItemInInventory(Items.MAP) >= 1)
        {
            LogUtils.log("Already have a map in inventory.");
            mapGrabed = true;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler chestScreen))
        {
            interactBlockLooking(chestPos, Direction.UP);
            return;
        }

        if (!itemGrabTimer.hasPassed((int)(itemTakeDelay.get() * 1000))) return;

        int chestSize = chestScreen.getRows() * 9;

        for (int slot = 0; slot < chestSize; slot++)
        {
            ItemStack stack = chestScreen.getSlot(slot).getStack();

            if (stack.isEmpty()) continue;
            if (stack.getItem() != Items.MAP) continue;

            int targetHotbarSlot = getSafeHotbarSlot();

            if (targetHotbarSlot == -1)
            {
                LogUtils.error("No safe hotbar slot available for map!");
                mc.player.closeHandledScreen();
                pause("No Hotbar Slot For Map");
                return;
            }

            mc.player.getInventory().setSelectedSlot(targetHotbarSlot);
            mc.interactionManager.clickSlot(chestScreen.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);

            itemGrabTimer.reset();
            LogUtils.log("Grabbed 1 empty map into hotbar slot " + targetHotbarSlot);
            mc.player.closeHandledScreen();
            mapGrabed = true;
            return;
        }

        LogUtils.error("No empty maps found in chest at " + chestPos);
        mc.player.closeHandledScreen();
        mapGrabed = false;
    }

    /***
     * grabOneGlassPane() opens the map chest (if not already open) and pulls a single glass pane.
     */
    private void grabOneGlassPane(BlockPos chestPos)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        if (countItemInInventory(Items.GLASS_PANE) >= 1)
        {
            LogUtils.log("Already have a glass pane in inventory.");
            glassPaneGrabbed = true;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler chestScreen))
        {
            interactBlockLooking(chestPos, Direction.UP);
            return;
        }

        if (!itemGrabTimer.hasPassed((int)(itemTakeDelay.get() * 1000))) return;

        int chestSize = chestScreen.getRows() * 9;

        for (int slot = 0; slot < chestSize; slot++)
        {
            ItemStack stack = chestScreen.getSlot(slot).getStack();

            if (stack.isEmpty()) continue;
            if (stack.getItem() != Items.GLASS_PANE) continue;

            int targetHotbarSlot = getSafeHotbarSlot();

            if (targetHotbarSlot == -1)
            {
                LogUtils.error("No safe hotbar slot available for glass pane!");
                mc.player.closeHandledScreen();
                pause("No Hotbar Slot For Glass Pane");
                return;
            }

            mc.player.getInventory().setSelectedSlot(targetHotbarSlot);
            mc.interactionManager.clickSlot(chestScreen.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);

            itemGrabTimer.reset();
            LogUtils.log("Grabbed 1 glass pane into hotbar slot " + targetHotbarSlot);
            mc.player.closeHandledScreen();
            glassPaneGrabbed = true;
            return;
        }

        LogUtils.error("No glass panes found in chest at " + chestPos);
        mc.player.closeHandledScreen();
        glassPaneGrabbed = false;
    }

    /***
     * getSafeHotbarSlot() returns a hotbar slot (FOOD_SLOT_COUNT..8) safe to put a working item in.
     * Hotbar slots 0..FOOD_SLOT_COUNT-1 are reserved for food and never returned.
     */
    private int getSafeHotbarSlot()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;

        for (int i = FOOD_SLOT_COUNT; i < 9; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
        }

        for (int i = FOOD_SLOT_COUNT; i < 9; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            Item      item  = stack.getItem();

            if (item == Items.GOLDEN_APPLE)  continue;
            if (item == Items.GOLDEN_CARROT) continue;
            if (item == Items.BREAD)         continue;

            return i;
        }

        return -1;
    }

    /***
     * getEmptyHotBarSlot() returns the first empty hotbar slot outside the food reserve, or -1.
     */
    private int getEmptyHotBarSlot()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;

        for (int i = FOOD_SLOT_COUNT; i < 9; i++)
        {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        return -1;
    }

    /***
     * getFreeInventorySlots() counts empty slots available for materials. The first FOOD_SLOT_COUNT
     * hotbar slots are reserved for food and never counted,
     */
    private int getFreeInventorySlots()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;

        int free = 0;

        for (int i = FOOD_SLOT_COUNT; i < 9; i++)
        {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }

        for (int i = 9; i <= 35; i++)
        {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    /***
     * getNextNeededEntry() returns the unsatisfied material with the smallest required count first,
     * or null when everything is satisfied or there are no free slots to fill.
     *
     */
    private Map.Entry<Item, Integer> getNextNeededEntry(int freeSlots)
    {
        if (freeSlots <= 0) return null;

        Map<Item, Integer> remaining = computeRemainingNeededMaterials();

        return remaining.entrySet().stream()
            .filter(e -> countItemInInventory(e.getKey()) < e.getValue())
            .min(Map.Entry.comparingByValue())
            .orElse(null);
    }

    /***
     * countItemInInventory() returns the total count of a given item across the player's inventory.
     */
    private int countItemInInventory(Item item)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item)
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /***
     * countTotalBlocks() returns the sum of every non-air material count in the schematic.
     */
    private int countTotalBlocks()
    {
        if (currentSchematic == null) return 0;

        MaterialListSchematic matList = new MaterialListSchematic(currentSchematic, true);
        return matList.getMaterialsAll().stream().mapToInt(MaterialListEntry::getCountTotal).sum();
    }

    /***
     * getTotalBlocks() populates matNeeded with the item-to-count map required by the active schematic.
     */
    private void getTotalBlocks()
    {
        if (currentSchematic == null) return;

        matNeeded.clear();

        MaterialListSchematic matList = new MaterialListSchematic(currentSchematic, true);

        for (MaterialListEntry entry : matList.getMaterialsAll())
        {
            matNeeded.put(entry.getStack().getItem(), entry.getCountTotal());
        }
    }
}
