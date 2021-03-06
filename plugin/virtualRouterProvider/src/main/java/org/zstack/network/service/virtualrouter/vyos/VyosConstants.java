package org.zstack.network.service.virtualrouter.vyos;

import org.zstack.header.network.service.NetworkServiceProviderType;
import org.zstack.utils.path.PathUtil;

/**
 * Created by xing5 on 2016/10/31.
 */
public interface VyosConstants {
    String VYOS_VM_TYPE = "vrouter";
    String VYOS_ROUTER_PROVIDER_TYPE = "vrouter";

    String ANSIBLE_PLAYBOOK_NAME = "zvr.py";
    String ANSIBLE_MODULE_PATH = "ansible/zvr";

    String PRIVATE_L3_FIREWALL_DEFAULT_ACTION = "reject";

    NetworkServiceProviderType PROVIDER_TYPE = new NetworkServiceProviderType(VyosConstants.VYOS_ROUTER_PROVIDER_TYPE);

    enum BootstrapInfoKey {
        vyosPassword
    }

    String VYOS_VERSION_PATH = "ansible/zvr/version";
    int VYOS_VERSION_LENGTH = 4;

    String REPLACE_FIREWALL_WITH_IPTBALES = "SkipVyosIptables";
    String HA_STATUS = "haStatus";

    String VIP_REBUILD_VERSION = "3.8.4.2";

    String VYOS_VMWARE_ALLOW_NIC_HOT_PLUGIN_VERSION = "4.2.0.0";
}
