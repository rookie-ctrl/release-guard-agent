package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.AgentEvent;
import com.interview.rag.agent.repository.AgentEventRepository;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

@Service
public class AgentEventService {

    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> liveStreams = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public AgentEventService(AgentEventRepository eventRepository, ObjectMapper objectMapper,
                             StringRedisTemplate redisTemplate) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public AgentEvent publish(String runId, String eventType, Object data) {
        Sinks.Many<ServerSentEvent<String>> stream = streamFor(runId);
        synchronized (stream) {
            AgentEvent event = new AgentEvent();
            event.setRunId(runId);
            event.setSequenceNumber(nextSequence(runId));
            event.setEventType(eventType);
            try {
                event.setDataJson(objectMapper.writeValueAsString(data));
            } catch (Exception e) {
                throw new IllegalStateException("SSE 事件序列化失败", e);
            }
            event = eventRepository.saveAndFlush(event);
            stream.tryEmitNext(toSse(event));
            return event;
        }
    }

    public Flux<ServerSentEvent<String>> stream(String runId, long afterSequence, boolean terminal) {
        var stored = eventRepository.findByRunIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                runId, afterSequence);
        Flux<ServerSentEvent<String>> history = Flux.fromIterable(stored).map(this::toSse);
        if (terminal) {
            return history;
        }
        long lastStoredSequence = stored.isEmpty() ? afterSequence
                : stored.get(stored.size() - 1).getSequenceNumber();
        return Flux.concat(history, streamFor(runId).asFlux()
                .filter(event -> Long.parseLong(event.id()) > lastStoredSequence));
    }

    public void complete(String runId) {
        Sinks.Many<ServerSentEvent<String>> stream = liveStreams.get(runId);
        if (stream != null) {
            stream.tryEmitComplete();
            liveStreams.remove(runId, stream);
        }
        redisTemplate.expire("agent:events:seq:" + runId, Duration.ofDays(7));
    }

    private long nextSequence(String runId) {
        String key = "agent:events:seq:" + runId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return redisTemplate.opsForValue().increment(key);
        }
        AgentEvent latest = eventRepository.findTopByRunIdOrderBySequenceNumberDesc(runId);
        long lastSequence = latest == null ? 0 : latest.getSequenceNumber();
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(lastSequence));
        return redisTemplate.opsForValue().increment(key);
    }

    private Sinks.Many<ServerSentEvent<String>> streamFor(String runId) {
        return liveStreams.computeIfAbsent(runId, ignored -> Sinks.many().replay().all());
    }

    private ServerSentEvent<String> toSse(AgentEvent event) {
        return ServerSentEvent.<String>builder().id(String.valueOf(event.getSequenceNumber()))
                .event(event.getEventType()).data(event.getDataJson()).build();
    }
}
