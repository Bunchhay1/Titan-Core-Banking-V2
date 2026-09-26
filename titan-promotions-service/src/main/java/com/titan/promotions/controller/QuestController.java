package com.titan.promotions.controller;

import com.titan.promotions.statemachine.QuestService;
import com.titan.promotions.statemachine.QuestState;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {
    private final QuestService questService;
    
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startQuest(@RequestParam Long accountId) {
        String questId = questService.startQuest(accountId);
        return ResponseEntity.ok(Map.of("questId", questId, "status", "QUEST_ACCEPTED"));
    }
    
    @PostMapping("/{questId}/progress")
    public ResponseEntity<Map<String, Object>> progressQuest(@PathVariable String questId) {
        boolean success = questService.progressQuest(questId);
        QuestState state = questService.getQuestState(questId);
        return ResponseEntity.ok(Map.of("success", success, "currentState", state));
    }
    
    @GetMapping("/{questId}/status")
    public ResponseEntity<Map<String, Object>> getQuestStatus(@PathVariable String questId) {
        QuestState state = questService.getQuestState(questId);
        return ResponseEntity.ok(Map.of("questId", questId, "state", state));
    }
}
