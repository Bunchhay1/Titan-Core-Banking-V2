package com.titan.promotions.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.data.redis.RedisStateMachineContextRepository;
import org.springframework.statemachine.data.redis.RedisStateMachinePersister;
import org.springframework.statemachine.persist.RepositoryStateMachinePersist;

@Configuration
@EnableStateMachineFactory
@Slf4j
public class QuestStateMachineConfig extends StateMachineConfigurerAdapter<QuestState, QuestEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<QuestState, QuestEvent> states) throws Exception {
        states.withStates()
            .initial(QuestState.QUEST_ACCEPTED)
            .state(QuestState.DAY_1_DONE)
            .state(QuestState.DAY_2_DONE)
            .state(QuestState.DAY_3_DONE)
            .state(QuestState.DAY_4_DONE)
            .state(QuestState.DAY_5_DONE)
            .state(QuestState.DAY_6_DONE)
            .state(QuestState.DAY_7_DONE)
            .end(QuestState.COMPLETED)
            .end(QuestState.FAILED)
            .end(QuestState.EXPIRED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<QuestState, QuestEvent> transitions) throws Exception {
        transitions
            .withExternal().source(QuestState.QUEST_ACCEPTED).target(QuestState.DAY_1_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_1_DONE).target(QuestState.DAY_2_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_2_DONE).target(QuestState.DAY_3_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_3_DONE).target(QuestState.DAY_4_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_4_DONE).target(QuestState.DAY_5_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_5_DONE).target(QuestState.DAY_6_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_6_DONE).target(QuestState.DAY_7_DONE).event(QuestEvent.COMPLETE_DAY)
            .and().withExternal().source(QuestState.DAY_7_DONE).target(QuestState.COMPLETED).event(QuestEvent.CLAIM_REWARD)
            .and().withExternal().source(QuestState.QUEST_ACCEPTED).target(QuestState.FAILED).event(QuestEvent.FAIL_QUEST)
            .and().withExternal().source(QuestState.QUEST_ACCEPTED).target(QuestState.EXPIRED).event(QuestEvent.EXPIRE_QUEST);
    }

    @Bean
    public RedisStateMachinePersister<QuestState, QuestEvent> redisPersister(RedisConnectionFactory connectionFactory) {
        RedisStateMachineContextRepository<QuestState, QuestEvent> repository = 
            new RedisStateMachineContextRepository<>(connectionFactory);
        return new RedisStateMachinePersister<>(new RepositoryStateMachinePersist<>(repository));
    }
}
