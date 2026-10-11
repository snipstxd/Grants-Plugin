package dev.grants.gui;

import dev.grants.GrantsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class MenuListener implements Listener {

    private final GrantsPlugin plugin;

    public MenuListener(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // event.getInventory() is the top inventory. getView() is avoided on purpose: InventoryView is a class
        // before 1.21 and an interface after, so calling into it breaks on one side or the other.
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof Menu)) return;
        final Menu menu = (Menu) top.getHolder();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        final Player player = (Player) event.getWhoClicked();
        if (event.getClickedInventory() != top) return;
        final int slot = event.getRawSlot();
        // Run next tick so opening/closing inventories from a click is safe.
        plugin.sync(() -> menu.click(player, slot));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) Menu.closed((Player) event.getPlayer(), event.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Menu.quit(event.getPlayer());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
}
