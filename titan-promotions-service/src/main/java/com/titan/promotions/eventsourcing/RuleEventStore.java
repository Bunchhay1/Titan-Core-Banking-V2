package com.titan.promotions.eventsourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
interface RuleEventRepository extends JpaRepository<RuleEvent, Long> {
    List<RuleEvent> findByRuleIdOrderByOccurredAtAsc(Long ruleId);
}

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEventStore {
    
    private final RuleEventRepository repository;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public void appendEvent(Long ruleId, String eventType, Object ruleSnapshot, String changedBy) {
        try {
            String payload = objectMapper.writeValueAsString(ruleSnapshot);
            
            RuleEvent event = RuleEvent.builder()
                .ruleId(ruleId)
                .eventType(eventType)
                .payload(payload)
                .changedBy(changedBy)
                .occurredAt(LocalDateTime.now())
                .build();
            
            repository.save(event);
            log.info("Rule event appended: ruleId={}, type={}, by={}", ruleId, eventType, changedBy);
        } catch (Exception e) {
            log.error("Failed to append rule event", e);
        }
    }
    
    public <T> T rebuildRuleState(Long ruleId, Class<T> ruleClass) {
        List<RuleEvent> events = repository.findByRuleIdOrderByOccurredAtAsc(ruleId);
        
        if (events.isEmpty()) {
            return null;
        }
        
        try {
            RuleEvent latestEvent = events.get(events.size() - 1);
            return objectMapper.readValue(latestEvent.getPayload(), ruleClass);
        } catch (Exception e) {
            log.error("Failed to rebuild rule state", e);
            return null;
        }
    }
}
