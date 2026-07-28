/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.benchmark;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Benchmark plugin for the BetterModel mesh-processing pipeline.
 * <p>
 * Provides commands to spawn N model-bearing entities in a grid, run a timed
 * measurement (tick MSPT, BetterModel worker CPU, GC pressure, allocation rate)
 * and export the results as CSV for before/after comparison.
 * </p>
 */
public final class BetterModelBenchmark extends JavaPlugin {

    private final List<UUID> spawned = new ArrayList<>();
    private BenchmarkMeasurement measurement;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        getLogger().info("BetterModel benchmark plugin enabled.");
    }

    @Override
    public void onDisable() {
        clearSpawned();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/bmbench spawn <model> <count> [spacing] | clear | measure <seconds> [label] | status");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "clear" -> {
                var count = clearSpawned();
                sender.sendMessage("Removed " + count + " benchmark entities.");
            }
            case "measure" -> measure(sender, args);
            case "status" -> status(sender);
            default -> sender.sendMessage("Unknown sub-command: " + args[0]);
        }
        return true;
    }

    private void spawn(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can spawn benchmark entities.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("/bmbench spawn <model> <count> [spacing]");
            return;
        }
        var modelName = args[1];
        var renderer = BetterModel.modelOrNull(modelName);
        if (renderer == null) {
            sender.sendMessage("Unknown model: " + modelName);
            return;
        }
        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("Invalid count: " + args[2]);
            return;
        }
        var spacing = 4.0;
        if (args.length >= 4) {
            try {
                spacing = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid spacing: " + args[3]);
                return;
            }
        }
        var world = player.getWorld();
        var base = player.getLocation();
        var side = (int) Math.ceil(Math.sqrt(count));
        var spawnedNow = 0;
        for (int i = 0; i < count; i++) {
            var x = (i % side - side / 2.0) * spacing;
            var z = ((double) i / side - side / 2.0) * spacing;
            var loc = base.clone().add(x, 0, z + 5);
            var entity = world.spawn(loc, org.bukkit.entity.Zombie.class, zombie -> {
                zombie.setAI(false);
                zombie.setSilent(true);
                zombie.setInvulnerable(true);
                zombie.setPersistent(true);
                zombie.setRemoveWhenFarAway(false);
            });
            var tracker = renderer.create(BukkitAdapter.adapt((LivingEntity) entity));
            tracker.animate("bench_loop");
            spawned.add(entity.getUniqueId());
            spawnedNow++;
        }
        sender.sendMessage("Spawned " + spawnedNow + " x " + modelName + " (total tracked: " + spawned.size() + ").");
    }

    private int clearSpawned() {
        var count = 0;
        for (var uuid : spawned) {
            var entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
                count++;
            }
        }
        spawned.clear();
        return count;
    }

    private void measure(@NotNull CommandSender sender, @NotNull String[] args) {
        if (measurement != null && measurement.isRunning()) {
            sender.sendMessage("A measurement is already running.");
            return;
        }
        var seconds = 30;
        if (args.length >= 2) {
            try {
                seconds = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid seconds: " + args[1]);
                return;
            }
        }
        var benchLabel = args.length >= 3 ? args[2] : "bench";
        measurement = new BenchmarkMeasurement(this, sender, seconds, benchLabel, spawned.size());
        measurement.start();
        sender.sendMessage("Measuring for " + seconds + "s (label: " + benchLabel + ", entities: " + spawned.size() + ")...");
    }

    private void status(@NotNull CommandSender sender) {
        var alive = spawned.stream().map(Bukkit::getEntity).filter(e -> e != null && e.isValid()).count();
        var workers = BenchmarkMeasurement.threadIdsByPrefix("BetterModel-Worker-").size();
        sender.sendMessage("Benchmark entities: " + alive + "/" + spawned.size()
            + ", BetterModel worker threads: " + workers
            + ", TPS(1m): " + String.format(Locale.ROOT, "%.2f", Bukkit.getTPS()[0]));
    }

    /**
     * Returns entities currently spawned by this plugin.
     *
     * @return valid spawned entities
     */
    public @NotNull List<Entity> spawnedEntities() {
        return spawned.stream().map(Bukkit::getEntity).filter(e -> e != null && e.isValid()).toList();
    }
}
