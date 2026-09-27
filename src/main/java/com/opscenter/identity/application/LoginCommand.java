package com.opscenter.identity.application;

/**
 * Input of {@code AuthenticationService.login}. {@code login} is a username or an email
 * (FR-IAM-01); {@code userAgent} is stored on the session for the "where am I logged in" view.
 * The record deliberately has no {@code toString} override exposing the password - never log it
 * (04-API §18).
 */
public record LoginCommand(String login, String password, String userAgent) {

    @Override
    public String toString() {
        return "LoginCommand[login=" + login + ", password=***]";
    }
}
