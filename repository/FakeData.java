package repository;

import java.util.List;
import model.WordTransition;

public class FakeData implements TransitionRepository {

    @Override
    public List<WordTransition> getTransitions(String word) {
        switch (word) {
            case "the":
                return List.of(
                    new WordTransition("cat", 7),
                    new WordTransition("dog", 2),
                    new WordTransition("bird", 1)
                );

        case "cat":
            return List.of(
                new WordTransition("sat", 5),
                new WordTransition("ran", 3),
                new WordTransition("jumped", 2)
            );

        case "dog":
            return List.of(
                new WordTransition("barked", 6),
                new WordTransition("chased", 3),
                new WordTransition("played", 1)
            );
        
        case "bird":
            return List.of(
                new WordTransition("flew", 6),
                new WordTransition("sang", 3),
                new WordTransition("nested", 1)
            );
        
        default:
            return List.of();
    } 
}
}
