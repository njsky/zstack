package org.zstack.test.integration.kvm.host

import org.springframework.http.HttpEntity
import org.zstack.compute.host.HostGlobalConfig
import org.zstack.compute.host.HostReconnectTask
import org.zstack.compute.host.HostTrackImpl
import org.zstack.core.cloudbus.CloudBus
import org.zstack.core.db.Q
import org.zstack.header.core.NoErrorCompletion
import org.zstack.header.host.*
import org.zstack.kvm.KVMAgentCommands
import org.zstack.kvm.KVMConstant
import org.zstack.kvm.KVMReconnectHostTask
import org.zstack.sdk.ClusterInventory
import org.zstack.sdk.HostInventory
import org.zstack.test.integration.kvm.KvmTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.FieldUtils
import org.zstack.utils.gson.JSONObjectUtil

import java.util.concurrent.TimeUnit

class KVMPingCase extends SubCase {
    EnvSpec env
    CloudBus bus

    @Override
    void clean() {
        env.delete()
    }

    static Closure<HostReconnectTask.CanDoAnswer> canDoReconnectFunc

    @Override
    void setup() {
        useSpring(KvmTest.springSpec)
    }

    @Override
    void environment() {
        env = makeEnv {
            zone {
                name = "zone"

                cluster {
                    name = "cluster"

                    kvm {
                        name = "kvm1"
                        managementIp = "127.0.0.1"
                        username = "root"
                        password = "password"
                    }

                    kvm {
                        name = "kvm2"
                        managementIp = "127.0.0.2"
                        username = "root"
                        password = "password"
                    }
                }
            }
        }
    }

    void waitHostStateChange(String hostUuid, HostState state) {
        retryInSecs {
            assert Q.New(HostVO.class).select(HostVO_.state).eq(HostVO_.uuid, hostUuid).findValue() == state
        }
    }

    void waitHostDisconnected(String hostUuid) {
        retryInSecs {
            assert Q.New(HostVO.class).select(HostVO_.status).eq(HostVO_.uuid, hostUuid).findValue() == HostStatus.Disconnected
        }
    }

    void waitHostConnected(String hostUuid) {
        retryInSecs {
            assert Q.New(HostVO.class).select(HostVO_.status).eq(HostVO_.uuid, hostUuid).findValue() == HostStatus.Connected
        }
    }

    void recoverHostToConnected(String hostUuid) {
        env.cleanSimulatorHandlers()
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.Ready }
        waitHostConnected(hostUuid)
    }

    void testHostReconnectAfterPingFailure() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.Ready }

        HostInventory kvm1 = env.inventoryByName("kvm1")

        boolean pingSuccess = false

        env.simulator(KVMConstant.KVM_PING_PATH) { HttpEntity<String> e, EnvSpec espec ->
            KVMAgentCommands.PingCmd cmd = JSONObjectUtil.toObject(e.getBody(), KVMAgentCommands.PingCmd.class)

            def rsp = new KVMAgentCommands.PingResponse()
            if (cmd.hostUuid == kvm1.uuid && !pingSuccess) {
                throw new RuntimeException("failure on purpose")
            } else {
                rsp.hostUuid = cmd.hostUuid
            }

            return rsp
        }

        waitHostDisconnected(kvm1.uuid)
        pingSuccess = true
        waitHostConnected(kvm1.uuid)
    }

    void testNoPingWhenHostMaintainedAndPingAfterEnabled() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.Ready }
        HostInventory kvm1 = env.inventoryByName("kvm1")

        waitHostConnected(kvm1.uuid)

        changeHostState {
            uuid = kvm1.uuid
            stateEvent = HostStateEvent.maintain
        }

        waitHostStateChange(kvm1.uuid, HostState.Maintenance)

        int count = 0

        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm1.uuid) {
                count ++
            }
        }

        TimeUnit.SECONDS.sleep(3L)
        assert count == 0

        changeHostState {
            uuid = kvm1.uuid
            stateEvent = HostStateEvent.enable
        }

        waitHostStateChange(kvm1.uuid, HostState.Enabled)

        count = 0

        retryInSecs {
            assert count > 0
        }

        cleanup()
        recoverHostToConnected(kvm1.uuid)
    }

    void testNoPingIfAutoReconnectIsFalse() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.Ready }

        boolean pingSuccess = false

        HostInventory kvm1 = env.inventoryByName("kvm1")

        env.simulator(KVMConstant.KVM_PING_PATH) { HttpEntity<String> e, EnvSpec espec ->
            KVMAgentCommands.PingCmd cmd = JSONObjectUtil.toObject(e.getBody(), KVMAgentCommands.PingCmd.class)

            def rsp = new KVMAgentCommands.PingResponse()
            if (cmd.hostUuid == kvm1.uuid && !pingSuccess) {
                throw new RuntimeException("failure on purpose")
            } else {
                rsp.hostUuid = cmd.hostUuid
            }

            return rsp
        }

        HostGlobalConfig.AUTO_RECONNECT_ON_ERROR.updateValue(false)
        waitHostDisconnected(kvm1.uuid)

        retryInSecs {
            assert Q.New(HostVO.class).select(HostVO_.status).eq(HostVO_.uuid, kvm1.uuid).findValue() == HostStatus.Disconnected
        }
        
        TimeUnit.SECONDS.sleep(2L)

        int count = 0

        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm1.uuid) {
                count ++
            }
        }

        TimeUnit.SECONDS.sleep(2L)
        assert count == 0

        HostGlobalConfig.AUTO_RECONNECT_ON_ERROR.updateValue(true)
        pingSuccess = true
        waitHostConnected(kvm1.uuid)

        cleanup()
        recoverHostToConnected(kvm1.uuid)
    }

    void testContinuePingIfHostNoReconnect() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.NoReconnect }

        HostInventory kvm1 = env.inventoryByName("kvm1")

        env.simulator(KVMConstant.KVM_PING_PATH) { HttpEntity<String> e, EnvSpec espec ->
            KVMAgentCommands.PingCmd cmd = JSONObjectUtil.toObject(e.getBody(), KVMAgentCommands.PingCmd.class)

            def rsp = new KVMAgentCommands.PingResponse()
            if (cmd.hostUuid == kvm1.uuid) {
                rsp.success = false
                rsp.error = "on purpose"
            } else {
                rsp.hostUuid = cmd.hostUuid
            }

            return rsp
        }

        waitHostDisconnected(kvm1.uuid)

        int count = 0

        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm1.uuid) {
                count ++
            }
        }

        retryInSecs {
            assert count > 0
        }

        cleanup()
        recoverHostToConnected(kvm1.uuid)
    }

    void testNoPingAfterHostDeleted() {
        ClusterInventory cluster = env.inventoryByName("cluster")

        HostInventory kvm = addKVMHost {
            clusterUuid = cluster.uuid
            managementIp = "127.0.0.3"
            name = "kvm3"
            username = "root"
            password = "password"
        }

        int count = 0
        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm.uuid) {
                count ++
            }
        }

        retryInSecs {
            assert count > 0
        }

        deleteHost { uuid = kvm.uuid }

        count = 0

        TimeUnit.SECONDS.sleep(3L)

        assert count == 0

        cleanup()
    }

    void testPingAfterRescanHost() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.Ready }

        HostInventory kvm1 = env.inventoryByName("kvm1")
        HostTrackImpl tracker = bean(HostTrackImpl.class)
        tracker.reScanHost()

        int count = 0

        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm1.uuid) {
                count ++
            }
        }

        retryInSecs {
            assert count > 0
        }

        cleanup()
        recoverHostToConnected(kvm1.uuid)
    }

    void testNoPingIfHostNotReadyToReconnect() {
        canDoReconnectFunc = {  HostReconnectTask.CanDoAnswer.NotReady }

        HostInventory kvm1 = env.inventoryByName("kvm1")

        env.simulator(KVMConstant.KVM_PING_PATH) { HttpEntity<String> e, EnvSpec espec ->
            KVMAgentCommands.PingCmd cmd = JSONObjectUtil.toObject(e.getBody(), KVMAgentCommands.PingCmd.class)

            def rsp = new KVMAgentCommands.PingResponse()
            if (cmd.hostUuid == kvm1.uuid) {
                rsp.success = false
                rsp.error = "on purpose"
            } else {
                rsp.hostUuid = cmd.hostUuid
            }

            return rsp
        }

        waitHostDisconnected(kvm1.uuid)

        int count = 0

        def cleanup = notifyWhenReceivedMessage(PingHostMsg.class) { PingHostMsg msg ->
            if (msg.hostUuid == kvm1.uuid) {
                count ++
            }
        }

        TimeUnit.SECONDS.sleep(3L)
        assert count == 0

        cleanup()
        recoverHostToConnected(kvm1.uuid)
    }

    static class HostReconnectTaskForTest extends HostReconnectTask {
        @Override
        protected HostReconnectTask.CanDoAnswer canDoReconnect() {
            if (canDoReconnectFunc != null) {
                return (HostReconnectTask.CanDoAnswer) canDoReconnectFunc()
            }

            return  HostReconnectTask.CanDoAnswer.NoReconnect
        }

        HostReconnectTaskForTest(String uuid, NoErrorCompletion completion) {
            super(uuid, completion)
        }
    }

    @Override
    void test() {
        bus = bean(CloudBus.class)

        env.create {
            HostGlobalConfig.PING_HOST_INTERVAL.updateValue(1)
            HostGlobalConfig.MAXIMUM_PING_FAILURE.updateValue(1)
            HostGlobalConfig.SLEEP_TIME_AFTER_PING_FAILURE.updateValue(0)

            functionForMockTestObjectFactory[HostReconnectTask.class] = {
                if (it instanceof KVMReconnectHostTask) {
                    return new HostReconnectTaskForTest(it.uuid, FieldUtils.getFieldValue("completion", it))
                } else {
                    return it
                }
            }

            onCleanExecute {
                functionForMockTestObjectFactory.remove(HostReconnectTask.class)
            }

            testNoPingAfterHostDeleted()
            testPingAfterRescanHost()
            testNoPingWhenHostMaintainedAndPingAfterEnabled()
            testNoPingIfAutoReconnectIsFalse()
            testHostReconnectAfterPingFailure()
            testContinuePingIfHostNoReconnect()
            testNoPingIfHostNotReadyToReconnect()

            canDoReconnectFunc = null
        }
    }
}
