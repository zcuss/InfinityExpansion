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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

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
     */
    private static final Set<String> BLOCKED_ITEMS = new HashSet<>(Arrays.asList(
            "BASIC_STORAGE",
            "ADVANCED_STORAGE",
            "REINFORCED_STORAGE",
            "VOID_STORAGE",
            "INFINITY_STORAGE"
            // NETWORK_QUANTUM handled via tolerant checks
    ));

    /**
     * Debug flag for previous versions — keep false in production.
     */
    private static final boolean DEBUG_ISBLOCKED = false;

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

        // Prevent blocked items from being inserted
        if (isBlocked(itemStack)) {
            return new int[0];
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

        // plugin-internal PDC (tetap ditulis)
        meta.getPersistentDataContainer().set(ITEM_KEY, PersistentType.ITEM_STACK_OLD, displayItem);
        meta.getPersistentDataContainer().set(AMOUNT_KEY, PersistentDataType.INTEGER, amount);
        if (storageId != null) {
            meta.getPersistentDataContainer().set(STORAGE_ID_KEY, PersistentDataType.STRING, storageId);
        }

        // write minecraft-namespaced keys ONLY when amount > 0
        if (amount > 0) {
            try {
                NamespacedKey mcStoredKey = NamespacedKey.minecraft("stored");
                NamespacedKey mcStorageIdKey = NamespacedKey.minecraft("storage_id");
                NamespacedKey mcInfiniteFlag = NamespacedKey.minecraft("infinite_storage");

                meta.getPersistentDataContainer().set(mcStoredKey, PersistentDataType.INTEGER, amount);

                if (storageId != null && !storageId.isEmpty()) {
                    meta.getPersistentDataContainer().set(mcStorageIdKey, PersistentDataType.STRING, storageId);
                }

                meta.getPersistentDataContainer().set(mcInfiniteFlag, PersistentDataType.BYTE, (byte) 1);
            } catch (Throwable ignored) {
                // safe-ignore
            }
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
     * PUBLIC supaya dapat dipanggil dari StorageCache.
     */
    public static boolean isBlocked(ItemStack stack) {
        if (stack == null) return false;

        // 1) Cek SlimefunItem langsung
        SlimefunItem sf = SlimefunItem.getByItem(stack);
        if (sf != null) {
            String id = null;
            try {
                id = sf.getId();
            } catch (Throwable ignored) {}

            if (id != null) {
                String uid = id.toUpperCase(Locale.ROOT);

                // exact match dari addon Infinite
                for (String blocked : BLOCKED_ITEMS) {
                    if (blocked.equalsIgnoreCase(uid) || blocked.equalsIgnoreCase(id)) {
                        return true;
                    }
                }

                // addon Network (NETWORK_QUANTUM_1..8) — tolerant checks
                if (uid.contains("NETWORK_QUANTUM")) return true;
                if (uid.contains("NETWORK-QUANTUM")) return true;
                if (uid.contains(":NETWORK_QUANTUM")) return true;
                if (uid.contains(":NETWORK-QUANTUM")) return true;

                // fallback sangat toleran
                if (uid.contains("NETWORK") && uid.contains("QUANTUM")) return true;
            }
        }

        // 2) Fallback: cek PDC (persistent data)
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            // display key -> sudah pasti storage
            if (pdc.has(DISPLAY_KEY, PersistentDataType.BYTE)) {
                return true;
            }

            // embedded inner item
            if (pdc.has(ITEM_KEY, PersistentType.ITEM_STACK_OLD)) {
                ItemStack inner = pdc.get(ITEM_KEY, PersistentType.ITEM_STACK_OLD);
                if (inner != null) {
                    SlimefunItem sfInner = SlimefunItem.getByItem(inner);
                    if (sfInner != null) {
                        String innerId = null;
                        try {
                            innerId = sfInner.getId();
                        } catch (Throwable ignored) {}

                        if (innerId != null) {
                            String uid = innerId.toUpperCase(Locale.ROOT);

                            for (String blocked : BLOCKED_ITEMS) {
                                if (blocked.equalsIgnoreCase(uid) || blocked.equalsIgnoreCase(innerId)) {
                                    return true;
                                }
                            }

                            if (uid.contains("NETWORK_QUANTUM")) return true;
                            if (uid.contains("NETWORK-QUANTUM")) return true;
                            if (uid.contains(":NETWORK_QUANTUM")) return true;
                            if (uid.contains(":NETWORK-QUANTUM")) return true;

                            if (uid.contains("NETWORK") && uid.contains("QUANTUM")) return true;
                        }
                    }
                }
            }

            // display name fallback
            if (meta.hasDisplayName()) {
                String dn = meta.getDisplayName().toUpperCase(Locale.ROOT);
                if (dn.contains("NETWORK") && dn.contains("QUANTUM")) return true;
                if (dn.contains("QUANTUM") && dn.contains("STORAGE")) return true;
            }

            // lore fallback
            if (meta.hasLore()) {
                for (String line : meta.getLore()) {
                    if (line == null) continue;
                    String up = line.toUpperCase(Locale.ROOT);
                    if (up.contains("NETWORK") && up.contains("QUANTUM")) return true;
                    if (up.contains("QUANTUM") && up.contains("STORAGE")) return true;
                }
            }
        }

        return false;
    }

}
