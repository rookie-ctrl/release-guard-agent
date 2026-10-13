package com.interview.rag.agent.gateway;

public class IdempotencyConflictException extends ApprovalRejectedException {
    public IdempotencyConflictException() { super("相同幂等键不能携带不同审批参数"); }
}
