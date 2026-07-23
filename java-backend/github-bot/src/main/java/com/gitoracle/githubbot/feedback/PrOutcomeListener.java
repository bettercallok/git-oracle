package com.gitoracle.githubbot.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PrOutcomeListener {

    private static final Logger logger = LoggerFactory.getLogger(PrOutcomeListener.class);
    private final FeedbackService feedbackService;

    public PrOutcomeListener(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @KafkaListener(topics = "github-pr-events", groupId = "github-bot-group")
    public void handlePrEvent(PrEvent event) {
        logger.info("Received PR Event for Job: {} (Type: {})", event.getJobId(), event.getType());
        
        FeedbackOutcome outcome;
        switch (event.getType()) {
            case "PR_MERGED":
                outcome = FeedbackOutcome.POSITIVE;
                break;
            case "PR_CLOSED":
                outcome = FeedbackOutcome.NEGATIVE;
                break;
            case "COMMIT_REVERTED":
                outcome = FeedbackOutcome.STRONG_NEGATIVE;
                break;
            case "REVIEW_APPROVED":
                outcome = FeedbackOutcome.STRONG_POSITIVE;
                break;
            default:
                logger.warn("Unknown PR event type: {}", event.getType());
                return;
        }
        
        feedbackService.record(event.getJobId(), outcome, event.getReviewer());
    }
}
