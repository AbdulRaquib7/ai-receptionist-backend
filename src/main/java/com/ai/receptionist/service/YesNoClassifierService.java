package com.ai.receptionist.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.ai.receptionist.utils.YesNoResult;

import java.util.Set;
import java.util.regex.Pattern;

@Service
public class YesNoClassifierService {

    private static final Set<String> AFFIRMATIVE_EXACT = Set.of(
            "yes","yeah","yep","ya","yup","ok","okay","sure","correct",
            "right","confirm","confirmed","go ahead","please do","do it"
    );

    private static final Set<String> NEGATIVE_EXACT = Set.of(
            "no","nope","nah","cancel","stop","never mind","nevermind",
            "dont","don't","wait","not now","not yet","hold on"
    );

    private static final Pattern YES_PATTERN = Pattern.compile(
            "\\b(yes|yeah|yep|ok|okay|sure|confirm|correct|right|go ahead|please do)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NO_PATTERN = Pattern.compile(
            "\\b(no|nope|nah|cancel|stop|never mind|dont|don't|wait|not now)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public YesNoResult classify(String input) {
        if (input == null || input.isBlank()) return YesNoResult.UNKNOWN;

        String text = input.trim().toLowerCase();

        if (text.length() <= 20) {
            if (AFFIRMATIVE_EXACT.contains(text)) return YesNoResult.YES;
            if (NEGATIVE_EXACT.contains(text)) return YesNoResult.NO;
        }

        boolean yes = YES_PATTERN.matcher(text).find();
        boolean no = NO_PATTERN.matcher(text).find();

        if (yes && !no) return YesNoResult.YES;
        if (no && !yes) return YesNoResult.NO;

        return YesNoResult.UNKNOWN;
    }

    public boolean isAffirmative(String input){
        return classify(input) == YesNoResult.YES;
    }

    public boolean isNegative(String input){
        return classify(input) == YesNoResult.NO;
    }
}
