package com.interview.rag.agent.gateway;

/** The external system explicitly rejected the request without creating an approval. */
public class ApprovalRejectedException extends IllegalArgumentException {
    public ApprovalRejectedException(String message) { super(message); }
}
