import java.util.Random;
import net.ankrya.zillion.transformation.EscrowTransfer;
import net.ankrya.zillion.transformation.TransformationTimeline;

/** Standalone server-transaction arithmetic tests. No JUnit, game bootstrap or Gradle dependency. */
public final class TransformationSafetyTest {
    public static void main(String[] args) {
        require(TransformationTimeline.DURATION_TICKS == 160, "duration contract");
        require(TransformationTimeline.ARMOR_TICK == 84, "armor timing contract");
        require(TransformationTimeline.DOCK_START_TICK == 104, "dock start contract");
        require(TransformationTimeline.DOCK_END_TICK == 138, "dock end contract");
        require(EscrowTransfer.movableCount(1, 64, 64) == 0, "full inventory must preserve pending original");
        require(EscrowTransfer.movableCount(1, 0, 1) == 1, "one empty armor-item stack accepts one original");
        require(EscrowTransfer.movableCount(64, 63, 64) == 1, "partial merge preserves overflow");
        require(EscrowTransfer.movableCount(1, 127, 64) == 0, "overfilled stack cannot produce negative transfer");
        require(EscrowTransfer.movableCount(0, 0, 64) == 0, "settled escrow cannot return twice");
        require(EscrowTransfer.movableCount(Integer.MAX_VALUE, 0, Integer.MAX_VALUE) == Integer.MAX_VALUE, "large counts do not overflow");

        Random random = new Random(0x5A1110L);
        for (int trial = 0; trial < 100_000; trial++) {
            int pending = 1 + random.nextInt(1024);
            int[] inventory = new int[36];
            for (int i = 0; i < inventory.length; i++) inventory[i] = random.nextInt(65);
            int before = pending + total(inventory);
            for (int retry = 0; retry < 4; retry++) {
                for (int i = 0; i < inventory.length; i++) {
                    int amount = EscrowTransfer.movableCount(pending, inventory[i], 64);
                    require(amount >= 0 && amount <= pending, "transfer range");
                    pending -= amount;
                    inventory[i] += amount;
                    require(inventory[i] <= 64, "slot capacity");
                }
                require(pending + total(inventory) == before, "retry must neither duplicate nor delete originals");
            }
        }
        System.out.println("TransformationSafetyTest: all checks passed (100000 randomized escrow retries).");
    }

    private static int total(int[] slots) {
        int count = 0;
        for (int slot : slots) count += slot;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
