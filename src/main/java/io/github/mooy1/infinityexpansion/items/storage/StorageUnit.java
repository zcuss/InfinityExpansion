package io.github.mooy1.infinityexpansion.items.storage;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.github.mooy1.infinityexpansion.InfinityExpansion;
import io.github.mooy1.infinityexpansion.categories.Groups;
import io.github.mooy1.infinitylib.common.PersistentType;
import io.github.mooy1.infinitylib.common.Scheduler;
import io.github.mooy1.infinitylib.machines.MenuBlock;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.core.attributes.DistinctiveItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.collections.Pair;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.ItemUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;

/**
 * A block that stored large amounts of 1 item
 *
 * @author Mooy1 (modded)
 *
 * Note: improved isBlocked(...) with debug logging and more tolerant NETWORK_QUANTUM detection.
 */
@ParametersAreNonnullByDefault
@SuppressWarnings("deprecation")
public final class StorageUnit extends MenuBlock implements DistinctiveItem {

    /* Namespaced keys */
    static final NamespacedKey EMPTY_KEY = InfinityExpansion.createKey("empty"); // key for empty item
    static final NamespacedKey DISPLAY_KEY = InfinityExpansion.createKey("display"); // key for display item
    private static final NamespacedKey ITEM_KEY = InfinityExpansion.createKey("item"); // item key for item pdc
    private static final NamespacedKey AMOUNT_KEY = InfinityExpansion.createKey("stored"); // amount key for item pdc
    private static final NamespacedKey STORAGE_ID_KEY = InfinityExpansion.createKey("storage_id"); // unique id

    /* BlockStorage key (string) */
    private static final String BLOCKSTORAGE_ID = "storage_id";

    /* Menu slots */
    static final int INPUT_SLOT = 10;
    static final int DISPLAY_SLOT = 13;
    static final int STATUS_SLOT = 4;
    static final int OUTPUT_SLOT = 16;
    static final int INTERACT_SLOT = 22;

    /* Menu items */
    private static final ItemStack INTERACTION_ITEM = new CustomItemStack(Material.LIME_STAINED_GLASS_PANE,
            "&aQuick Actions",
            "&bLeft Click: &7Withdraw 1 item",
            "&bRight Click: &7Withdraw 1 stack",
            "&bShift Left Click: &7Deposit inventory",
            "&bShift Right Click: &7Withdraw inventory"
    );
    private static final ItemStack LOADING_ITEM = new CustomItemStack(Material.CYAN_STAINED_GLASS_PANE,
            "&bStatus",
            "&7Loading..."
    );

    /* Instance constants */
    private final Map<Location, StorageCache> caches = new HashMap<>();
    final int max;

    /**
     * ======= BLOCKED ITEMS CONFIG =======
     * Daftar ID Slimefun yang tidak boleh dimasukkan ke StorageUnit.
     * NOTE: gunakan ID Slimefun (bukan material name). Untuk network quantum 1..8
     * kita support wildcard via contains/upper-case matching.
     */
    private static final Set<String> BLOCKED_ITEMS = new HashSet<>(Arrays.asList(
            "BASIC_STORAGE",
            "ADVANCED_STORAGE",
            "REINFORCED_STORAGE",
            "VOID_STORAGE",
            "INFINITY_STORAGE"
            // NETWORK_QUANTUM handled via contains / tolerant checks
    ));

    /**
     * Debug flag: set true untuk menyalakan logging tambahan tentang item yang diperiksa.
     * Matikan (false) saat sudah selesai debugging.
     */
    private static final boolean DEBUG_ISBLOCKED = true;

    public StorageUnit(SlimefunItemStack item, int max, ItemStack[] recipe) {
        super(Groups.STORAGE, item, StorageForge.TYPE, recipe);
        this.max = max;

        addItemHandler(new BlockTicker() {

            @Override
            public boolean isSynchronized() {
                return true;
            }

            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                StorageCache cache = StorageUnit.this.caches.get(b.getLocation());
                if (cache != null) {
                    cache.tick(b);
                }
            }

        }, new BlockBreakHandler(false, false) {

            @Override
            public void onPlayerBreak(BlockBreakEvent e, ItemStack item, List<ItemStack> drops) {
                BlockMenu menu = BlockStorage.getInventory(e.getBlock());
                StorageCache cache = StorageUnit.this.caches.remove(menu.getLocation());
                if (cache != null && !cache.isEmpty()) {
                    cache.destroy(e, drops);
                }
                else {
                    drops.add(getItem().clone());
                }
                menu.dropItems(menu.getLocation(), INPUT_SLOT, OUTPUT_SLOT);
            }

        });
    }

    @Override
    protected void onNewInstance(@Nonnull BlockMenu menu, @Nonnull Block b) {
        if (BlockStorage.getInventory(b) == menu) {
            // ensure storage id exists in block storage (generate if not)
            String existing = BlockStorage.getLocationInfo(b.getLocation()).getString(BLOCKSTORAGE_ID);
            if (existing == null) {
                String uuid = UUID.randomUUID().toString();
                BlockStorage.addBlockInfo(b, BLOCKSTORAGE_ID, uuid);
            }
            this.caches.put(b.getLocation(), new StorageCache(this, menu));
        }
    }

    @Nonnull
    @Override
    public Collection<ItemStack> getDrops() {
        return Collections.emptyList();
    }

    @Override
    protected void onPlace(@Nonnull BlockPlaceEvent e, @Nonnull Block b) {
        // Load stored item/amount if present on item
        Pair<ItemStack, Integer> data = loadFromStack(e.getItemInHand());
        if (data != null) {
            Scheduler.run(() -> {
                StorageCache cache = this.caches.get(b.getLocation());
                cache.load(data.getFirstValue(), data.getFirstValue().getItemMeta());
                // gunakan setter yang umum; sesuaikan ke StorageCache jika method berbeda
                cache.setAmount(data.getSecondValue());
            });
        }

        // If placing item had a stored storage id, transfer it to BlockStorage
        if (e.getItemInHand().hasItemMeta()) {
            ItemMeta meta = e.getItemInHand().getItemMeta();
            String id = meta.getPersistentDataContainer().get(STORAGE_ID_KEY, PersistentDataType.STRING);
            if (id != null && !id.isEmpty()) {
                BlockStorage.addBlockInfo(b, BLOCKSTORAGE_ID, id);
            }
        }
    }

    @Override
    protected void setup(@Nonnull BlockMenuPreset blockMenuPreset) {
        blockMenuPreset.drawBackground(INPUT_BORDER, new int[] {
                0, 1, 2, 9, 11, 18, 19, 20
        });
        blockMenuPreset.drawBackground(BACKGROUND_ITEM, new int[] {
                3, 5, 12, 14, 21, 23
        });
        blockMenuPreset.drawBackground(OUTPUT_BORDER, new int[] {
                6, 7, 8, 15, 17, 24, 25, 26
        });
        blockMenuPreset.addMenuClickHandler(DISPLAY_SLOT, ChestMenuUtils.getEmptyClickHandler());
        blockMenuPreset.addItem(INTERACT_SLOT, INTERACTION_ITEM);
        blockMenuPreset.addItem(STATUS_SLOT, LOADING_ITEM);
    }

    @Nonnull
    @Override
    protected int[] getInputSlots(DirtyChestMenu dirtyChestMenu, ItemStack itemStack) {

        // ===========================
        // Prevent blocked items from being inserted
        // ===========================
        if (isBlocked(itemStack)) {
            return new int[0]; // blocked -> no input slots
        }

        StorageCache cache = this.caches.get(((BlockMenu) dirtyChestMenu).getLocation());
        if (cache != null && (cache.isEmpty() || cache.matches(itemStack))) {
            cache.input();
            return new int[] { INPUT_SLOT };
        }
        else {
            return new int[0];
        }
    }

    @Override
    protected int[] getInputSlots() {
        return new int[] { INPUT_SLOT };
    }

    @Override
    protected int[] getOutputSlots() {
        return new int[] { OUTPUT_SLOT };
    }

    public void reloadCache(Block b) {
        this.caches.get(b.getLocation()).reloadData();
    }

    @Nullable
    public StorageCache getCache(Location location) {
        return this.caches.get(location);
    }

    /**
     * Return the storage id for this location, or null if none.
     */
    @Nullable
    public static String getStorageId(Location location) {
        if (location == null) return null;
        return BlockStorage.getLocationInfo(location).getString(BLOCKSTORAGE_ID);
    }

    static void transferToStack(@Nonnull ItemStack source, @Nonnull ItemStack target) {
        Pair<ItemStack, Integer> data = loadFromStack(source);
        if (data != null) {
            target.setItemMeta(saveToStack(target.getItemMeta(), data.getFirstValue(),
                    ItemUtils.getItemName(data.getFirstValue()), data.getSecondValue()));
        }
    }

    /**
     * Save meta + (optionally) attach storage id to the dropped item meta.
     */
    static ItemMeta saveToStack(ItemMeta meta, ItemStack displayItem, String displayName, int amount) {
        return saveToStackWithId(meta, displayItem, displayName, amount, null);
    }

    static ItemMeta saveToStackWithId(ItemMeta meta, ItemStack displayItem, String displayName, int amount, @Nullable String storageId) {
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            lore.add(ChatColor.GOLD + "Stored: " + displayName + ChatColor.YELLOW + " x " + amount);
            meta.setLore(lore);
        }
        meta.getPersistentDataContainer().set(ITEM_KEY, PersistentType.ITEM_STACK_OLD, displayItem);
        meta.getPersistentDataContainer().set(AMOUNT_KEY, PersistentDataType.INTEGER, amount);
        if (storageId != null) {
            meta.getPersistentDataContainer().set(STORAGE_ID_KEY, PersistentDataType.STRING, storageId);
        }
        return meta;
    }

    @Nullable
    private static Pair<ItemStack, Integer> loadFromStack(ItemStack source) {
        if (source.hasItemMeta()) {
            PersistentDataContainer con = source.getItemMeta().getPersistentDataContainer();
            Integer amount = con.get(AMOUNT_KEY, PersistentDataType.INTEGER);
            if (amount != null) {
                ItemStack item = con.get(ITEM_KEY, PersistentType.ITEM_STACK_OLD);
                if (item != null) {
                    return new Pair<>(item, amount);
                }
            }
        }
        return null;
    }

    @Override
    public boolean canStack(@Nonnull ItemMeta sfItemMeta, @Nonnull ItemMeta itemMeta) {
        return sfItemMeta.getPersistentDataContainer().equals(itemMeta.getPersistentDataContainer());
    }

    /**
     * Helper: check apakah sebuah ItemStack termasuk item yang dilarang.
     * Perbaikan:
     * - tolerant terhadap variasi ID (case-insensitive, contains)
     * - fallback cek DISPLAY_KEY, ITEM_KEY, displayName, dan lore
     * - debug logging opsional untuk melihat ID / nama item yang diperiksa
     *
     * PUBLIC supaya dapat dipanggil dari StorageCache.
     */
    public static boolean isBlocked(ItemStack stack) {
        if (stack == null) return false;

        // helper untuk log debug
        final java.util.function.Consumer<String> dbg = msg -> {
            if (DEBUG_ISBLOCKED) {
                Bukkit.getLogger().log(Level.INFO, "[StorageUnit:isBlocked] " + msg);
            }
        };

        // 1) Cek SlimefunItem langsung
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(stack);
        if (sf != null) {
            String id = null;
            try {
                id = sf.getId();
            } catch (Throwable ignored) {}
            dbg.accept("SlimefunItem detected; raw id = " + id + " ; item = " + stack);
            if (id != null) {
                String uid = id.toUpperCase(Locale.ROOT);

                // cek exact match pada BLOCKED_ITEMS (case-insensitive)
                for (String blocked : BLOCKED_ITEMS) {
                    if (blocked.equalsIgnoreCase(uid) || blocked.equalsIgnoreCase(id)) {
                        dbg.accept("Blocked by exact BLOCKED_ITEMS match: " + id);
                        return true;
                    }
                }

                // tolerant checks: contains NETWORK_QUANTUM / NETWORK-QUANTUM / :NETWORK_QUANTUM
                if (uid.contains("NETWORK_QUANTUM") || uid.contains("NETWORK-QUANTUM") || uid.contains(":NETWORK_QUANTUM") || uid.contains(":NETWORK-QUANTUM")) {
                    dbg.accept("Blocked by NETWORK_QUANTUM pattern: " + id);
                    return true;
                }

                // also check if id contains simple 'QUANTUM' and 'NETWORK' words (very tolerant)
                if (uid.contains("QUANTUM") && uid.contains("NETWORK")) {
                    dbg.accept("Blocked by combined keywords in id: " + id);
                    return true;
                }
            }
        } else {
            dbg.accept("Not a SlimefunItem (getByItem returned null) for item = " + stack);
        }

        // 2) Fallback: cek PersistentDataContainer
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            // jika item punya label display (ini item yang kita pakai untuk tampilan)
            if (pdc.has(DISPLAY_KEY, PersistentDataType.BYTE)) {
                dbg.accept("Blocked: has DISPLAY_KEY (treat as storage display)");
                return true;
            }

            // jika item punya ITEM_KEY (embedded item), ambil dan cek
            if (pdc.has(ITEM_KEY, PersistentType.ITEM_STACK_OLD)) {
                ItemStack inner = pdc.get(ITEM_KEY, PersistentType.ITEM_STACK_OLD);
                if (inner != null) {
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfInner = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(inner);
                    String innerId = null;
                    if (sfInner != null) {
                        try {
                            innerId = sfInner.getId();
                        } catch (Throwable ignored) {}
                        dbg.accept("Embedded inner item detected; inner id = " + innerId + " ; inner = " + inner);
                        if (innerId != null) {
                            String uid = innerId.toUpperCase(Locale.ROOT);
                            for (String blocked : BLOCKED_ITEMS) {
                                if (blocked.equalsIgnoreCase(uid) || blocked.equalsIgnoreCase(innerId)) {
                                    dbg.accept("Blocked by embedded exact BLOCKED_ITEMS match: " + innerId);
                                    return true;
                                }
                            }
                            if (uid.contains("NETWORK_QUANTUM") || uid.contains("NETWORK-QUANTUM") || uid.contains(":NETWORK_QUANTUM") || uid.contains(":NETWORK-QUANTUM")) {
                                dbg.accept("Blocked by embedded NETWORK_QUANTUM pattern: " + innerId);
                                return true;
                            }
                            if (uid.contains("QUANTUM") && uid.contains("NETWORK")) {
                                dbg.accept("Blocked by embedded combined keywords in innerId: " + innerId);
                                return true;
                            }
                        }
                    } else {
                        dbg.accept("Embedded inner item is not a SlimefunItem (getByItem returned null) for inner = " + inner);
                    }
                }
            }

            // 3) Fallback ekstra: cek display name / lore untuk kata kunci
            if (meta.hasDisplayName()) {
                String dn = meta.getDisplayName().toUpperCase(Locale.ROOT);
                dbg.accept("DisplayName check: " + meta.getDisplayName());
                if (dn.contains("NETWORK") && dn.contains("QUANTUM")) {
                    dbg.accept("Blocked by displayName containing NETWORK + QUANTUM: " + meta.getDisplayName());
                    return true;
                }
                if (dn.contains("QUANTUM") && dn.contains("STORAGE")) {
                    dbg.accept("Blocked by displayName containing QUANTUM + STORAGE: " + meta.getDisplayName());
                    return true;
                }
            }
            if (meta.hasLore()) {
                for (String line : meta.getLore()) {
                    String up = line == null ? "" : line.toUpperCase(Locale.ROOT);
                    if ((up.contains("NETWORK") && up.contains("QUANTUM")) || (up.contains("QUANTUM") && up.contains("STORAGE"))) {
                        dbg.accept("Blocked by lore line: " + line);
                        return true;
                    }
                }
            }
        }

        // Tidak terdeteksi sebagai blocked
        dbg.accept("Not blocked: item allowed = " + stack);
        return false;
    }
}
