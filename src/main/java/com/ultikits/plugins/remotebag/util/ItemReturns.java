package com.ultikits.plugins.remotebag.util;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The items a bag page window was given beyond what its page held, and how they go back to the player
 * (UltiKits/UltiRemoteBag#54; maintainer decision of 2026-10-06: a save that is not stored gives back the items put
 * in; items taken out are not taken back).
 * <p>
 * One implementation for the open window ({@code RemoteBagContentGUI}) and for a kept write that the database
 * refused after its window closed ({@code BagEditClaimService}), so both count and return in the same way.
 */
public final class ItemReturns {

    private ItemReturns() {
    }

    /**
     * The items {@code shown} holds beyond {@code base}, by type and amount: moving an item within the page counts as
     * nothing; every way an item can enter the window (click, shift-click, drag, number key, off-hand swap) counts.
     *
     * @param base  what the page held (what the window read, or what its last save wrote); may be {@code null}
     * @param shown what the window holds now; may be {@code null}
     * @return the items put in, never {@code null}
     */
    public static List<ItemStack> putIn(ItemStack[] base, ItemStack[] shown) {
        List<ItemStack> read = new ArrayList<>();
        if (base != null) {
            for (ItemStack item : base) {
                if (isRealItem(item)) {
                    read.add(item);
                }
            }
        }
        int[] unmatched = new int[read.size()];
        for (int i = 0; i < read.size(); i++) {
            unmatched[i] = read.get(i).getAmount();
        }
        List<ItemStack> putIn = new ArrayList<>();
        if (shown == null) {
            return putIn;
        }
        for (ItemStack item : shown) {
            if (!isRealItem(item)) {
                continue;
            }
            int amount = item.getAmount();
            for (int i = 0; i < read.size() && amount > 0; i++) {
                if (unmatched[i] > 0 && read.get(i).isSimilar(item)) {
                    int matched = Math.min(amount, unmatched[i]);
                    unmatched[i] -= matched;
                    amount -= matched;
                }
            }
            if (amount > 0) {
                ItemStack given = item.clone();
                given.setAmount(amount);
                putIn.add(given);
            }
        }
        return putIn;
    }

    /**
     * Gives {@code items} to {@code player}: into their inventory, and what does not fit at their feet; one console
     * line names the items, the player and the page, and the player is told. Main thread only.
     *
     * @param player    the player the items go back to
     * @param plugin    the module, for its language file and logger
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @param items     the items; nothing happens when empty
     */
    public static void giveBack(Player player, UltiToolsPlugin plugin, UUID ownerUuid, int page, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftOver = player.getInventory().addItem(copies(items));
        int dropped = 0;
        for (ItemStack rest : leftOver.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            dropped += rest.getAmount();
        }
        plugin.getLogger().warn(plugin.i18n("log_bag_items_returned")
                .replace("{PAGE}", String.valueOf(page))
                .replace("{DROPPED}", String.valueOf(dropped))
                .replace("{ITEMS}", describe(items))
                .replace("{OWNER}", String.valueOf(ownerUuid))
                // The player's name last: it is data, so a brace sequence inside it stays as written.
                .replace("{PLAYER}", String.valueOf(player.getName())));
        player.sendMessage(ChatColor.YELLOW + plugin.i18n("msg_items_returned"));
    }

    /**
     * Items as one line for the console: type and amount, comma-separated; {@code -} for none.
     *
     * @param items the items
     * @return the description
     */
    public static String describe(List<ItemStack> items) {
        StringBuilder text = new StringBuilder();
        for (ItemStack item : items) {
            if (!isRealItem(item)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(item.getType().name()).append(" x").append(item.getAmount());
        }
        return text.length() == 0 ? "-" : text.toString();
    }

    /**
     * The items of a page as a list, for {@link #describe}.
     *
     * @param page the page's slots; may hold {@code null}
     * @return the real items, in slot order
     */
    public static List<ItemStack> itemsOf(ItemStack[] page) {
        List<ItemStack> items = new ArrayList<>();
        if (page != null) {
            for (ItemStack item : page) {
                if (isRealItem(item)) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    private static ItemStack[] copies(List<ItemStack> items) {
        ItemStack[] copies = new ItemStack[items.size()];
        for (int i = 0; i < copies.length; i++) {
            copies[i] = items.get(i).clone();
        }
        return copies;
    }

    private static boolean isRealItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }
}
