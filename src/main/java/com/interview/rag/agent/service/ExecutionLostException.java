package com.interview.rag.agent.service;

/** A stale worker must exit without changing the replacement worker's state. */
public class ExecutionLostException extends RuntimeException {
    public ExecutionLostException(String message) { super(message); }
}
