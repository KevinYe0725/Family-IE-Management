package com.familyfinance.ledger.recurring;

import com.familyfinance.shared.ResourceConflictException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringGenerator {
    // Bounds one rule to ten years of monthly catch-up per daily scheduler invocation.
    static final int MAX_OCCURRENCES_PER_RULE_PER_RUN = 120;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RecurringRuleRepository rules;
    private final RecurringOccurrenceRepository occurrences;
    private final Clock clock;

    public RecurringGenerator(
            RecurringRuleRepository rules, RecurringOccurrenceRepository occurrences, Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
    }

    @Transactional
    public int generateDueOccurrences() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        int created = 0;
        for (RecurringRule rule : rules
                .findByActiveTrueAndPausedFalseAndNextDueOnLessThanEqualOrderByIdAsc(today)) {
            LocalDate cutoff = rule.getEndOn() == null || rule.getEndOn().isAfter(today)
                    ? today : rule.getEndOn();
            int processed = 0;
            LocalDate due = rule.getNextDueOn();
            while (due != null
                    && !due.isAfter(cutoff)
                    && processed < MAX_OCCURRENCES_PER_RULE_PER_RUN) {
                if (!occurrences.existsByRuleIdAndDueOn(rule.getId(), due)) {
                    occurrences.save(new RecurringOccurrence(rule, due));
                    created++;
                }
                processed++;
                due = RecurrenceCalculator.nextDue(rule, due);
            }
            rule.advanceTo(RecurrenceCalculator.withinEnd(due, rule.getEndOn()));
        }
        try {
            occurrences.flush();
            rules.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RECURRENCE_RACE", "周期发生项已由另一任务生成，请重试");
        }
        return created;
    }
}
