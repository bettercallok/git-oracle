package com.gitoracle.githubbot.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    
    private static final Logger logger = LoggerFactory.getLogger(FeedbackService.class);

    public void record(String jobId, FeedbackOutcome outcome, String reviewer) {
        // In Layer 9b, this will publish a message back to the Python AI to update its memory.
        // For Layer 9a, we simply log the tracking of the outcome.
        logger.info("================ RLHF FEEDBACK RECORDED ================");
        logger.info("Job ID:   {}", jobId);
        logger.info("Reviewer: {}", reviewer);
        logger.info("Outcome:  {}", outcome);
        
        switch (outcome) {
            case POSITIVE:
            case STRONG_POSITIVE:
                logger.info("Action: Reinforcing successful AI approach.");
                break;
            case NEGATIVE:
            case STRONG_NEGATIVE:
                logger.warn("Action: Penalizing failed AI approach to prevent repeating mistakes.");
                break;
        }
        logger.info("========================================================");
    }
}
