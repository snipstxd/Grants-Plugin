package dev.grants.model;

import java.util.UUID;

public final class Grant {

    public enum Status { ACTIVE, EXPIRED, REMOVED }

    private final UUID id;
    private final UUID target;
    private final String targetName;
    private final String group;
    private final String grantedBy;
    private final long grantedAt;
    /** Epoch millis, or 0 for permanent. */
    private final long expiresAt;
    private final String reason;

    private Status status;
    private long endedAt;
    private String removedBy;

    public Grant(UUID id, UUID target, String targetName, String group, String grantedBy,
                 long grantedAt, long expiresAt, String reason,
                 Status status, long endedAt, String removedBy) {
        this.id = id;
        this.target = target;
        this.targetName = targetName;
        this.group = group;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
        this.reason = reason;
        this.status = status;
        this.endedAt = endedAt;
        this.removedBy = removedBy;
    }

    public UUID id() { return id; }
    public UUID target() { return target; }
    public String targetName() { return targetName; }
    public String group() { return group; }
    public String grantedBy() { return grantedBy; }
    public long grantedAt() { return grantedAt; }
    public long expiresAt() { return expiresAt; }
    public String reason() { return reason; }
    public Status status() { return status; }
    public long endedAt() { return endedAt; }
    public String removedBy() { return removedBy; }

    /** Snapshot for saving on another thread while the original may still change. */
    public Grant copy() {
        return new Grant(id, target, targetName, group, grantedBy, grantedAt, expiresAt, reason, status, endedAt, removedBy);
    }

    public boolean isPermanent() { return expiresAt <= 0; }
    public boolean isActive() { return status == Status.ACTIVE; }

    public void expire() {
        status = Status.EXPIRED;
        endedAt = expiresAt;
    }

    public void remove(String by, long at) {
        status = Status.REMOVED;
        removedBy = by;
        endedAt = at;
    }
}
