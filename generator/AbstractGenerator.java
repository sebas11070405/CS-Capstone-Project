/*
 * Contains functionality shared by generators, such as cleaning and validating sentences.
 */

package generator;

public abstract class AbstractGenerator implements Generator{

    protected int maxWords;

    // Constructor for AbstractGenerator, takes in the maximum number of words to generate
    public AbstractGenerator(int maxWords){

        this.maxWords = maxWords;
    }

   // cleanSentence method removes any extra spaces and punctuation from the sentence
    protected String cleanSentence(String sentence){
        
        return sentence.trim();
    }

    // isValidSentence method checks if the sentence is valid (not null and not blank)
    protected boolean isValidSentence(String sentence){
    
        return sentence != null && !sentence.isBlank();
    }
}