package repository;

import model.WordTransition;
import java.util.List;

// It is an interface that doesn't need to know how the data is stored.
// Data access abstraction layer

public interface TransitionRepository {

    List<WordTransition> getTransitions(String word);
}