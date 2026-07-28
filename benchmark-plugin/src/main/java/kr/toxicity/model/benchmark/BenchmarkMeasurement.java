/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.benchmark;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One timed measurement window.
 * <p>
 * Collects per-tick main-thread busy time via {@link ServerTickStartEvent}/{@link ServerTickEndEvent},
 * CPU time of named thread groups (BetterModel workers, server thread, Netty IO), GC counts/time and
 * JVM-wide allocated bytes. Prints a summary and appends one CSV row per run for before/after diffing.
 * </p>
 */
final class BenchmarkMeasurement implements Listener {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] TRACKED_PREFIXES = {
        "BetterModel-Worker-",
        "Server thread",
        "Netty"
    };

    private final Plugin plugin;
    private final CommandSender sender;
    private final int seconds;
    private final String label;
    private final int entityCount;

    private final List<Long> tickDurationsNanos = new ArrayList<>(20 * 60);
    private long tickStartNanos = -1;
    private long windowStartNanos;
    private long[] cpuStart;
    private List<List<Long>> threadGroups;
    private long gcCountStart, gcTimeStart;
    private long allocatedStart = -1;
    private boolean running;

    BenchmarkMeasurement(@NotNull Plugin plugin, @NotNull CommandSender sender, int seconds, @NotNull String label, int entityCount) {
        this.plugin = plugin;
        this.sender = sender;
        this.seconds = Math.max(seconds, 5);
        this.label = label;
        this.entityCount = entityCount;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        running = true;
        windowStartNanos = System.nanoTime();
        threadGroups = new ArrayList<>();
        cpuStart = new long[TRACKED_PREFIXES.length];
        for (int i = 0; i < TRACKED_PREFIXES.length; i++) {
            var ids = threadIdsByPrefix(TRACKED_PREFIXES[i]);
            threadGroups.add(ids);
            cpuStart[i] = cpuTimeSum(ids);
        }
        gcCountStart = gcCount();
        gcTimeStart = gcTime();
        allocatedStart = allocatedBytes();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, this::finish, seconds * 20L);
    }

    @EventHandler
    public void onTickStart(@NotNull ServerTickStartEvent event) {
        tickStartNanos = System.nanoTime();
    }

    @EventHandler
    public void onTickEnd(@NotNull ServerTickEndEvent event) {
        if (tickStartNanos > 0) tickDurationsNanos.add(System.nanoTime() - tickStartNanos);
    }

    private void finish() {
        running = false;
        HandlerList.unregisterAll(this);
        var wallNanos = System.nanoTime() - windowStartNanos;

        var sorted = new ArrayList<>(tickDurationsNanos);
        sorted.sort(Long::compare);
        var avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1.0e6;
        var p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95)) / 1.0e6;
        var max = sorted.isEmpty() ? 0 : sorted.getLast() / 1.0e6;
        var tps = Bukkit.getTPS()[0];

        var cpuLines = new StringBuilder();
        var cpuCsv = new StringBuilder();
        for (int i = 0; i < TRACKED_PREFIXES.length; i++) {
            // Restrict to threads that existed at window start so the CPU delta is consistent.
            var ids = threadIdsByPrefix(TRACKED_PREFIXES[i]);
            ids.retainAll(threadGroups.get(i));
            var cpuMs = (cpuTimeSum(ids) - cpuStart[i]) / 1.0e6;
            var pct = 100.0 * (cpuMs * 1.0e6) / wallNanos;
            cpuLines.append(String.format(Locale.ROOT, "  %-22s %10.1f ms CPU (%5.1f%% of wall)%n", TRACKED_PREFIXES[i], cpuMs, pct));
            cpuCsv.append(String.format(Locale.ROOT, ",%.1f", cpuMs));
        }

        var gcCountDelta = gcCount() - gcCountStart;
        var gcTimeDelta = gcTime() - gcTimeStart;
        var allocated = allocatedBytes();
        var allocRate = allocatedStart >= 0 && allocated >= 0
            ? (allocated - allocatedStart) / 1.0e6 / (wallNanos / 1.0e9)
            : -1;

        var summary = String.format(Locale.ROOT,
            """
            === BetterModel benchmark [%s] ===
            entities: %d, window: %ds, ticks sampled: %d
            TPS(1m): %.2f | MSPT avg: %.2f  p95: %.2f  max: %.2f
            %sGC: %d collections, %d ms | alloc rate: %s
            """,
            label, entityCount, seconds, sorted.size(),
            tps, avg, p95, max,
            cpuLines,
            gcCountDelta, gcTimeDelta,
            allocRate < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f MB/s", allocRate));
        for (var line : summary.split("\n")) sender.sendMessage(line);
        plugin.getLogger().info(summary);

        var csv = plugin.getDataFolder().toPath().resolve("results.csv");
        var row = String.format(Locale.ROOT, "%s,%s,%d,%d,%.2f,%.2f,%.2f,%.2f%s,%d,%d,%.1f%n",
            LocalDateTime.now().format(TIME_FORMAT), label, entityCount, seconds,
            tps, avg, p95, max, cpuCsv, gcCountDelta, gcTimeDelta, allocRate);
        try {
            if (Files.notExists(csv)) {
                Files.writeString(csv, "time,label,entities,seconds,tps,mspt_avg,mspt_p95,mspt_max,cpu_bm_worker_ms,cpu_server_thread_ms,cpu_netty_ms,gc_count,gc_time_ms,alloc_mb_s\n",
                    StandardOpenOption.CREATE);
            }
            Files.writeString(csv, row, StandardOpenOption.APPEND);
            sender.sendMessage("Appended results to " + csv);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write " + csv + ": " + e.getMessage());
        }
    }

    static @NotNull List<Long> threadIdsByPrefix(@NotNull String prefix) {
        var bean = ManagementFactory.getThreadMXBean();
        var list = new ArrayList<Long>();
        for (var info : bean.getThreadInfo(bean.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith(prefix)) list.add(info.getThreadId());
        }
        return list;
    }

    private static long cpuTimeSum(@NotNull List<Long> ids) {
        var bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) return 0;
        if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
        var sum = 0L;
        for (var id : ids) {
            var time = bean.getThreadCpuTime(id);
            if (time > 0) sum += time;
        }
        return sum;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(b.getCollectionCount(), 0)).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(b.getCollectionTime(), 0)).sum();
    }

    private static long allocatedBytes() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun) || !sun.isThreadAllocatedMemorySupported()) return -1;
        if (!sun.isThreadAllocatedMemoryEnabled()) sun.setThreadAllocatedMemoryEnabled(true);
        var sum = 0L;
        for (var id : sun.getAllThreadIds()) {
            var bytes = sun.getThreadAllocatedBytes(id);
            if (bytes > 0) sum += bytes;
        }
        return sum;
    }
}
