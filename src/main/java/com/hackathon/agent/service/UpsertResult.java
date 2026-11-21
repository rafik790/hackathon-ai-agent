package com.hackathon.agent.service;

/**
 * Outcome of persisting one batch of snapshots.
 */
public record UpsertResult(int created, int updated, int unchanged) {

    public static final UpsertResult EMPTY = new UpsertResult(0, 0, 0);

    public UpsertResult plus(UpsertResult other) {
        return new UpsertResult(created + other.created, updated + other.updated, unchanged + other.unchanged);
    }
}
