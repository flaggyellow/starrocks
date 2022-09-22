// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/mysql/MysqlProto.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.mysql;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.starrocks.analysis.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static com.starrocks.mysql.MysqlHandshakePacket.AUTHENTICATION_KERBEROS_CLIENT;

// MySQL protocol util
public class MysqlProto {
    private static final Logger LOG = LogManager.getLogger(MysqlProto.class);

    // scramble: data receive from server.
    // randomString: data send by server in plug-in data field
    // user_name#HIGH@cluster_name
    private static boolean authenticate(ConnectContext context, byte[] scramble, byte[] randomString, String user) {
        String usePasswd = scramble.length == 0 ? "NO" : "YES";

        if (user == null || user.isEmpty()) {
            ErrorReport.report(ErrorCode.ERR_ACCESS_DENIED_ERROR, "", usePasswd);
            return false;
        }

        String remoteIp = context.getMysqlChannel().getRemoteIp();

        if (context.getGlobalStateMgr().isUsingNewPrivilege()) {
            if (Config.enable_auth_check) {
                UserIdentity currentUser = context.getGlobalStateMgr().getAuthenticationManager().checkPassword(
                        user, remoteIp, scramble, randomString);
                if (currentUser == null) {
                    ErrorReport.report(ErrorCode.ERR_ACCESS_DENIED_ERROR, user, usePasswd);
                    return false;
                }
                context.setAuthDataSalt(randomString);
                context.setCurrentUserIdentity(currentUser);
                context.setQualifiedUser(user);
            }
            return true;
        }
        List<UserIdentity> currentUserIdentity = Lists.newArrayList();
        if (!GlobalStateMgr.getCurrentState().getAuth().checkPassword(user, remoteIp,
                scramble, randomString, currentUserIdentity)) {
            ErrorReport.report(ErrorCode.ERR_ACCESS_DENIED_ERROR, user, usePasswd);
            return false;
        }
        context.setAuthDataSalt(randomString);
        if (Config.enable_auth_check) {
            context.setCurrentUserIdentity(currentUserIdentity.get(0));
        }
        context.setQualifiedUser(user);
        return true;
    }

    // send response packet(OK/EOF/ERR).
    // before call this function, should set information in state of ConnectContext
    public static void sendResponsePacket(ConnectContext context) throws IOException {
        MysqlSerializer serializer = context.getSerializer();
        MysqlChannel channel = context.getMysqlChannel();
        MysqlPacket packet = context.getState().toResponsePacket();

        // send response packet to client
        serializer.reset();
        packet.writeTo(serializer);
        channel.sendAndFlush(serializer.toByteBuffer());
    }

    /**
     * negotiate with client, use MySQL protocol
     * server ---handshake---> client
     * server <--- authenticate --- client
     * server --- response(OK/ERR) ---> client
     * Exception:
     * IOException:
     */
    public static boolean negotiate(ConnectContext context) throws IOException {
        MysqlSerializer serializer = context.getSerializer();
        MysqlChannel channel = context.getMysqlChannel();
        context.getState().setOk();

        // Server send handshake packet to client.
        serializer.reset();
        MysqlHandshakePacket handshakePacket = new MysqlHandshakePacket(context.getConnectionId());
        handshakePacket.writeTo(serializer);
        channel.sendAndFlush(serializer.toByteBuffer());

        // Server receive authenticate packet from client.
        ByteBuffer handshakeResponse = channel.fetchOnePacket();
        if (handshakeResponse == null) {
            // receive response failed.
            return false;
        }
        MysqlAuthPacket authPacket = new MysqlAuthPacket();
        if (!authPacket.readFrom(handshakeResponse)) {
            ErrorReport.report(ErrorCode.ERR_NOT_SUPPORTED_AUTH_MODE);
            sendResponsePacket(context);
            return false;
        }

        // check capability
        if (!MysqlCapability.isCompatible(context.getServerCapability(), authPacket.getCapability())) {
            // TODO: client return capability can not support
            ErrorReport.report(ErrorCode.ERR_NOT_SUPPORTED_AUTH_MODE);
            sendResponsePacket(context);
            return false;
        }

        // Starting with MySQL 8.0.4, MySQL changed the default authentication plugin for MySQL client
        // from mysql_native_password to caching_sha2_password.
        // ref: https://mysqlserverteam.com/mysql-8-0-4-new-default-authentication-plugin-caching_sha2_password/
        // So, User use mysql client or ODBC Driver after 8.0.4 have problem to connect to StarRocks
        // with password.
        // So StarRocks support the Protocol::AuthSwitchRequest to tell client to keep the default password plugin
        // which StarRocks is using now.
        //
        // Older version mysql client does not send auth plugin info, like 5.1 version.
        // So we check if auth plugin name is null and treat as mysql_native_password if is null.
        String authPluginName = authPacket.getPluginName();
        if (authPluginName != null && !handshakePacket.checkAuthPluginSameAsStarRocks(authPluginName)) {
            // 1. clear the serializer
            serializer.reset();
            // 2. build the auth switch request and send to the client
            if (authPluginName.equals(AUTHENTICATION_KERBEROS_CLIENT)) {
                if (GlobalStateMgr.getCurrentState().getAuth().isSupportKerberosAuth()) {
                    try {
                        handshakePacket.buildKrb5AuthRequest(serializer, context.getRemoteIP(), authPacket.getUser());
                    } catch (Exception e) {
                        ErrorReport.report("Building handshake with kerberos error, msg: %s", e.getMessage());
                        sendResponsePacket(context);
                        return false;
                    }
                } else {
                    ErrorReport.report(ErrorCode.ERR_AUTH_PLUGIN_NOT_LOADED, "authentication_kerberos");
                    sendResponsePacket(context);
                    return false;
                }
            } else {
                handshakePacket.buildAuthSwitchRequest(serializer);
            }
            channel.sendAndFlush(serializer.toByteBuffer());
            // Server receive auth switch response packet from client.
            ByteBuffer authSwitchResponse = channel.fetchOnePacket();
            if (authSwitchResponse == null) {
                // receive response failed.
                return false;
            }
            // 3. the client use default password plugin of StarRocks to dispose
            // password
            authPacket.setAuthResponse(readEofString(authSwitchResponse));
        }

        // change the capability of serializer
        context.setCapability(context.getServerCapability());
        serializer.setCapability(context.getCapability());

        // NOTE: when we behind proxy, we need random string sent by proxy.
        byte[] randomString = handshakePacket.getAuthPluginData();
        // check authenticate
        if (!authenticate(context, authPacket.getAuthResponse(), randomString, authPacket.getUser())) {
            sendResponsePacket(context);
            return false;
        }

        // set database
        String db = authPacket.getDb();
        if (!Strings.isNullOrEmpty(db)) {
            try {
                GlobalStateMgr.getCurrentState().changeCatalogDb(context, db);
            } catch (DdlException e) {
                sendResponsePacket(context);
                return false;
            }
        }
        return true;
    }

    /**
     * Change user command use MySQL protocol
     * Exception:
     * IOException:
     */
    public static boolean changeUser(ConnectContext context, ByteBuffer buffer) throws IOException {
        // parse change user packet
        MysqlChangeUserPacket changeUserPacket = new MysqlChangeUserPacket(context.getCapability());
        if (!changeUserPacket.readFrom(buffer)) {
            ErrorReport.report(ErrorCode.ERR_NOT_SUPPORTED_AUTH_MODE);
            sendResponsePacket(context);
            // reconstruct serializer with context capability
            context.getSerializer().setCapability(context.getCapability());
            return false;
        }
        // save previous user login info
        UserIdentity priviousUserIdentity = context.getCurrentUserIdentity();
        String priviousQualifiedUser = context.getQualifiedUser();
        String priviousResourceGroup = context.getSessionVariable().getResourceGroup();
        // do authenticate again
        if (!authenticate(context, changeUserPacket.getAuthResponse(), context.getAuthDataSalt(),
                changeUserPacket.getUser())) {
            LOG.warn("Command `Change user` failed, from [{}] to [{}]. ", priviousQualifiedUser,
                    changeUserPacket.getUser());
            sendResponsePacket(context);
            // reconstruct serializer with context capability
            context.getSerializer().setCapability(context.getCapability());
            // recover from privious user login info
            context.getSessionVariable().setResourceGroup(priviousResourceGroup);
            return false;
        }
        // set database
        String db = changeUserPacket.getDb();
        if (!Strings.isNullOrEmpty(db)) {
            try {
                GlobalStateMgr.getCurrentState().changeCatalogDb(context, db);
            } catch (DdlException e) {
                LOG.error("Command `Change user` failed at stage changing db, from [{}] to [{}], err[{}] ",
                        priviousQualifiedUser, changeUserPacket.getUser(), e.getMessage());
                sendResponsePacket(context);
                // reconstruct serializer with context capability
                context.getSerializer().setCapability(context.getCapability());
                // recover from privious user login info
                context.getSessionVariable().setResourceGroup(priviousResourceGroup);
                context.setCurrentUserIdentity(priviousUserIdentity);
                context.setQualifiedUser(priviousQualifiedUser);
                return false;
            }
        }
        LOG.info("Command `Change user` succeeded, from [{}] to [{}]. ", priviousQualifiedUser,
                context.getQualifiedUser());
        return true;
    }

    public static byte readByte(ByteBuffer buffer) {
        return buffer.get();
    }

    public static int readInt1(ByteBuffer buffer) {
        return readByte(buffer) & 0XFF;
    }

    public static int readInt2(ByteBuffer buffer) {
        return (readByte(buffer) & 0xFF) | ((readByte(buffer) & 0xFF) << 8);
    }

    public static int readInt3(ByteBuffer buffer) {
        return (readByte(buffer) & 0xFF) | ((readByte(buffer) & 0xFF) << 8) | ((readByte(
                buffer) & 0xFF) << 16);
    }

    public static int readInt4(ByteBuffer buffer) {
        return (readByte(buffer) & 0xFF) | ((readByte(buffer) & 0xFF) << 8) | ((readByte(
                buffer) & 0xFF) << 16) | ((readByte(buffer) & 0XFF) << 24);
    }

    public static long readInt6(ByteBuffer buffer) {
        return (readInt4(buffer) & 0XFFFFFFFFL) | (((long) readInt2(buffer)) << 32);
    }

    public static long readInt8(ByteBuffer buffer) {
        return (readInt4(buffer) & 0XFFFFFFFFL) | (((long) readInt4(buffer)) << 32);
    }

    public static long readVInt(ByteBuffer buffer) {
        int b = readInt1(buffer);

        if (b < 251) {
            return b;
        }
        if (b == 252) {
            return readInt2(buffer);
        }
        if (b == 253) {
            return readInt3(buffer);
        }
        if (b == 254) {
            return readInt8(buffer);
        }
        if (b == 251) {
            throw new NullPointerException();
        }
        return 0;
    }

    public static byte[] readFixedString(ByteBuffer buffer, int len) {
        byte[] buf = new byte[len];
        buffer.get(buf);
        return buf;
    }

    public static byte[] readEofString(ByteBuffer buffer) {
        byte[] buf = new byte[buffer.remaining()];
        buffer.get(buf);
        return buf;
    }

    public static byte[] readLenEncodedString(ByteBuffer buffer) {
        long length = readVInt(buffer);
        byte[] buf = new byte[(int) length];
        buffer.get(buf);
        return buf;
    }

    public static byte[] readNulTerminateString(ByteBuffer buffer) {
        int oldPos = buffer.position();
        int nullPos = oldPos;
        for (nullPos = oldPos; nullPos < buffer.limit(); ++nullPos) {
            if (buffer.get(nullPos) == 0) {
                break;
            }
        }
        byte[] buf = new byte[nullPos - oldPos];
        buffer.get(buf);
        // skip null byte.
        buffer.get();
        return buf;
    }

}
