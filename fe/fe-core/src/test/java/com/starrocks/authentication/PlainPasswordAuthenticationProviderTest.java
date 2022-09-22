// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.authentication;

import com.starrocks.analysis.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.mysql.MysqlPassword;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PlainPasswordAuthenticationProviderTest {
    protected PlainPasswordAuthenticationProvider provider = new PlainPasswordAuthenticationProvider();

    @Test
    public void testValidPassword() throws Exception {
        Config.enable_validate_password = true;

        // too short
        try {
            provider.validatePassword("aaa");
            Assert.fail();
        } catch (AuthenticationException e) {
            Assert.assertTrue(e.getMessage().contains("password is too short"));
        }

        // only number
        String[] badPasswords = {"starrocks", "STARROCKS", "123456789", "STARROCKS123", "starrocks123", "STARROCKSstar"};
        for (String badPassword : badPasswords) {
            try {
                provider.validatePassword(badPassword);
                Assert.fail();
            } catch (AuthenticationException e) {
                Assert.assertTrue(e.getMessage().contains(
                        "password should contains at least one digit, one lowercase letter and one uppercase letter!"));
            }
        }

        provider.validatePassword("aaaAAA123");
        Config.enable_validate_password = false;
        provider.validatePassword("aaa");
    }

    @Test
    public void testAuthentica() throws Exception {
        UserIdentity testUser = UserIdentity.createAnalyzedUserIdentWithIp("test", "%");
        String[] passwords = {"asdf123", "starrocks", "testtest"};
        byte[] seed = "petals on a wet black bough".getBytes(StandardCharsets.UTF_8);
        for (String password : passwords) {
            UserAuthenticationInfo info = provider.validAuthenticationInfo(testUser, password, null);
            byte[] scramble = MysqlPassword.scramble(seed, password);
            provider.authenticate(testUser.getQualifiedUser(), "10.1.1.1", scramble, seed, info);
        }

        // no password
        UserAuthenticationInfo info = provider.validAuthenticationInfo(testUser, "", null);
        provider.authenticate(testUser.getQualifiedUser(), "10.1.1.1", new byte[0], new byte[0], info);
        try {
            provider.authenticate(
                    testUser.getQualifiedUser(),
                    "10.1.1.1",
                    "xx".getBytes(StandardCharsets.UTF_8),
                    "x".getBytes(StandardCharsets.UTF_8),
                    info);
            Assert.fail();
        } catch (AuthenticationException e) {
            Assert.assertTrue(e.getMessage().contains("password length mismatch!"));
        }

        info = provider.validAuthenticationInfo(testUser, "bb", null);
        try {
            provider.authenticate(
                    testUser.getQualifiedUser(),
                    "10.1.1.1",
                    MysqlPassword.scramble(seed, "xx"),
                    seed,
                    info);
            Assert.fail();
        } catch (AuthenticationException e) {
            Assert.assertTrue(e.getMessage().contains("password mismatch!"));
        }

    }
}
