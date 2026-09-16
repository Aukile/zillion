package net.ankrya.zillion.transformation;

/** Small dependency-free conservation rule shared by the real escrow transfer and server tests. */
public final class EscrowTransfer {
    private EscrowTransfer() {}

    /** Returns the count transferred, leaving all overflow owned by persistent escrow. */
    public static int movableCount(int remaining, int present, int capacity) {
        if (remaining <= 0 || present < 0 || capacity <= present) return 0;
        return Math.min(remaining, capacity - present);
    }
}
