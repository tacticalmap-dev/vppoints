package com.flowingsun.vppoints.cohmode;

import java.util.*;

/**
 * Core matchmaking logic for CohMode.
 * Balances players into two teams based on KD and class restrictions.
 */
public class CohMatchmaker {

    public record QueueEntry(UUID playerId, CohClass preferredClass) {
    }

    public record MatchResult(
            List<UUID> blueTeam,
            List<UUID> redTeam,
            Map<UUID, CohClass> assignedClasses
    ) {
    }

    /**
     * Balances a list of players into two teams.
     * 1. Priority for Commander role.
     * 2. Balanced KD using a greedy approach.
     */
    public static MatchResult balancePlayers(List<QueueEntry> queue) {
        List<UUID> blueTeam = new ArrayList<>();
        List<UUID> redTeam = new ArrayList<>();
        Map<UUID, CohClass> assignedClasses = new HashMap<>();

        // 1. Separate Commanders and others
        List<QueueEntry> commanderCandidates = new ArrayList<>();
        List<QueueEntry> others = new ArrayList<>();

        for (QueueEntry entry : queue) {
            if (entry.preferredClass() == CohClass.COMMANDER) {
                commanderCandidates.add(entry);
            } else {
                others.add(entry);
            }
        }

        // Sort both by KD descending
        commanderCandidates.sort(Comparator.comparingDouble((QueueEntry e) -> CohStatsManager.INSTANCE.getKD(e.playerId())).reversed());
        others.sort(Comparator.comparingDouble((QueueEntry e) -> CohStatsManager.INSTANCE.getKD(e.playerId())).reversed());

        // 2. Assign Commanders (max 1 per team)
        if (!commanderCandidates.isEmpty()) {
            QueueEntry blueCmd = commanderCandidates.remove(0);
            blueTeam.add(blueCmd.playerId());
            assignedClasses.put(blueCmd.playerId(), CohClass.COMMANDER);
        }
        if (!commanderCandidates.isEmpty()) {
            QueueEntry redCmd = commanderCandidates.remove(0);
            redTeam.add(redCmd.playerId());
            assignedClasses.put(redCmd.playerId(), CohClass.COMMANDER);
        }

        // Any leftover commander candidates become normal assault players
        others.addAll(commanderCandidates);
        others.sort(Comparator.comparingDouble((QueueEntry e) -> CohStatsManager.INSTANCE.getKD(e.playerId())).reversed());

        // 3. Greedy Balance others
        double blueKdSum = getKdSum(blueTeam);
        double redKdSum = getKdSum(redTeam);

        for (QueueEntry entry : others) {
            if (blueKdSum <= redKdSum) {
                blueTeam.add(entry.playerId());
                blueKdSum += CohStatsManager.INSTANCE.getKD(entry.playerId());
            } else {
                redTeam.add(entry.playerId());
                redKdSum += CohStatsManager.INSTANCE.getKD(entry.playerId());
            }
            assignedClasses.put(entry.playerId(), entry.preferredClass());
        }

        return new MatchResult(blueTeam, redTeam, assignedClasses);
    }

    private static double getKdSum(List<UUID> team) {
        return team.stream().mapToDouble(CohStatsManager.INSTANCE::getKD).sum();
    }
}
