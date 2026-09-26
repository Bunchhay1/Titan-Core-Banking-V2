package com.titan.promotions.statemachine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuestService {
    private final StateMachineFactory<QuestState, QuestEvent> factory;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "quest:state:";

    public String startQuest(Long accountId) {
        String questId = "QUEST-" + accountId + "-" + UUID.randomUUID();
        StateMachine<QuestState, QuestEvent> sm = factory.getStateMachine(questId);
        sm.startReactively().block();
        persist(sm, questId);
        log.info("Quest started: {} for account {}", questId, accountId);
        return questId;
    }

    public boolean progressQuest(String questId) {
        try {
            StateMachine<QuestState, QuestEvent> sm = restore(questId);
            var msg = MessageBuilder.withPayload(QuestEvent.COMPLETE_DAY).build();
            var result = sm.sendEvent(reactor.core.publisher.Mono.just(msg)).blockLast();
            boolean accepted = result != null &&
                result.getResultType() == StateMachineEventResult.ResultType.ACCEPTED;
            persist(sm, questId);
            log.info("Quest {} progressed to {}", questId, sm.getState().getId());
            return accepted;
        } catch (Exception e) {
            log.error("Failed to progress quest {}", questId, e);
            return false;
        }
    }

    public QuestState getQuestState(String questId) {
        try {
            return restore(questId).getState().getId();
        } catch (Exception e) {
            log.error("Failed to get quest state", e);
            return null;
        }
    }

    private void persist(StateMachine<QuestState, QuestEvent> sm, String questId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + questId, sm.getState().getId().name());
    }

    private StateMachine<QuestState, QuestEvent> restore(String questId) {
        StateMachine<QuestState, QuestEvent> sm = factory.getStateMachine(questId);
        String savedState = (String) redisTemplate.opsForValue().get(KEY_PREFIX + questId);
        if (savedState != null) {
            sm.startReactively().block();
        }
        return sm;
    }
}
