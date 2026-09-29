package com.interview.rag.mq;

import com.interview.rag.service.DocumentParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 解析任务消费者
 *
 * 面试要点:消费者内部吞掉异常不重抛——
 * 解析失败已落库为 FAILED 状态,重投只会重复失败并阻塞队列,所以选择"记状态 + 用户重传"而非无限重试
 */
@Slf4j
@Component
public class ParseMessageConsumer {

    private final DocumentParseService documentParseService;

    public ParseMessageConsumer(DocumentParseService documentParseService) {
        this.documentParseService = documentParseService;
    }

    @RabbitListener(queues = "${rag.mq.queue}")
    public void onMessage(Long documentId) {
        log.info("收到解析任务: documentId={}", documentId);
        documentParseService.process(documentId);
    }
}
