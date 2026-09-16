package com.south13oy.qct.ui;

import com.south13oy.qct.util.MenuBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 玩家周围可交互容器/工作方块的一条记录。
 */
public record NearbyEntry(
        BlockPos pos,
        BlockState state,
        MenuProvider provider,
        BlockEntity blockEntity,
        double distSq
) {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 是否为容器类（有存储实体或容器菜单）。工作台等无存储为 false。 */
    public boolean isContainer() {
        return blockEntity instanceof Container || provider instanceof Container;
    }

    public static List<NearbyEntry> scan(Level level, BlockPos center, int radius) {
        List<NearbyEntry> result = new ArrayList<>();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int countNotLoaded = 0, countAir = 0, countNoProvider = 0;
        String noProviderSample = "n/a";
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!level.isLoaded(p)) { countNotLoaded++; continue; }
                    BlockState bs = level.getBlockState(p);
                    if (bs.isAir()) { countAir++; continue; }
                    // 统一经 MenuBridge 解析：常规方块 getMenuProvider，末影箱走特判兜底，
                    // 否则 provider==null 会把末影箱从扫描里漏掉（上方容器数少 1）。
                    MenuProvider provider = MenuBridge.providerFor(level, p, bs);
                    if (provider == null) {
                        countNoProvider++;
                        if (countNoProvider <= 2) {
                            noProviderSample = bs.getBlock().toString() + "@" + p.toShortString();
                        }
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(p);
                    result.add(new NearbyEntry(p.immutable(), bs, provider, be,
                            p.distToCenterSqr(cx + 0.5, cy + 0.5, cz + 0.5)));
                }
            }
        }
        LOGGER.debug("QCT scan center={} radius={} notLoaded={} air={} noProvider={} sample={} found={}",
                center.toShortString(), radius, countNotLoaded, countAir, countNoProvider, noProviderSample, result.size());
        // 排序：容器在前、工作方块在后；同一类内按距离由近到远
        result.sort(Comparator
                .comparing((NearbyEntry e) -> e.isContainer() ? 0 : 1)
                .thenComparingDouble(NearbyEntry::distSq));
        return result;
    }
}
