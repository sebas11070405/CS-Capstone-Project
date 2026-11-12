package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;
import java.util.*;

// Define method to retrieve possible next words given two preceding words
public interface TrigramRepo {

    List<WordTransition> getTrigramTransitions(
            String previousWord,
            String currentWord
    );

}
