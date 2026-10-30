package com.sentencebuilder.autocomplete;

import com.sentencebuilder.autocomplete.Autocomplete;
import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.FakeData;
import org.junit.jupiter.api.Test;

public class AutocompleteTest {

    @Test
    public static void main(String[] args){
        FakeData data = new FakeData();
        Autocomplete autocomplete = new Autocomplete(data);

        //Record existing transition
        autocomplete.recordChoice("cat", "ran");

        //Record transition that doesn't exist yet
        autocomplete.recordChoice("cat", "dreamed");



        for(WordTransition transition : data.getTransitions("cat")){
            System.out.println(transition.getNextWord() + " | frequency: " + transition.getFrequency() + " | selected: " + transition.getUserSelected());
        }

        System.out.println(data.wordExists("dreamed"));

        autocomplete.recordChoice("unusual", "word");
        System.out.println(data.wordExists("unusual"));
        System.out.println(data.wordExists("word"));
    }
}
