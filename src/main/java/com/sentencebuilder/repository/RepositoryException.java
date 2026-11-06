/*
 * Thrown when a database query fails. Unchecked, so generators and the UI can
 * call the repository without handling SQL errors, and so a fake repository
 * in tests doesn't have to declare them.
 */

package com.sentencebuilder.repository;

public class RepositoryException extends RuntimeException
{

    public RepositoryException(String message, Throwable cause)
    {
        super(message, cause);
    }
}