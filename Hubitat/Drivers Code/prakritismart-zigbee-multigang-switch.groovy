/**
 *  Prakriti Smart ZigBee Wall Switch Multi-Gang
 *  Device Driver for Hubitat Elevation hub
 *
 *  Based on kkossev's driver Version 0.4.1, last updated 2023-02-10
 *
 *  https://github.com/kkossev/hubitat-muxa-fork/blob/master/drivers/zemismart-zigbee-multigang-switch.groovy
 *
 *  Ver. 0.0.1 2023-04-05 Prakriti Smart    - first version
 *  
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.transform.Field
 
def version() { "1.0.0" }
def timeStamp() { "2023/04/05 9:36 AM" }

metadata {
    definition (name: "Prakriti Smart ZigBee Wall Switch Multi-Gang", namespace: "prakritismart", author: "Prakriti Smart", importUrl: "https://raw.githubusercontent.com/prakritiSmart/SmartHome/main/Hubitat/Drivers%20Code/prakritismart-zigbee-multigang-switch.groovy", singleThreaded: true ) {
        capability "Initialize"
        capability "Actuator"
        capability "Configuration"
        capability "Switch"
        capability "Refresh"
        capability "Health Check"
        
        command "configure", [[name: "Configure the sensor after switching drivers"]]
        command "initialize", [[name: "Initialize the sensor after switching drivers.  \n\r   ***** Will load device default values! *****" ]]
        command "refresh",   [[name: "May work for some DC/mains powered sensors only"]] 
        
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0004,0005,0006,0702,0B04,E000,E001", outClusters:"0019,000A", model:"TS011F", manufacturer:"_TZ3000_yf8iuzil", deviceJoinName: "Prakriti Smart multi-gang switch"
    }
    
    preferences {

            input (name: "logEnable", type: "bool", title: "Debug logging", description: "<i>Debug information, useful for troubleshooting. Recommended value is <b>false</b></i>", defaultValue: true)
            input (name: "txtEnable", type: "bool", title: "Description text logging", description: "<i>Display sensor states in HE log page. Recommended value is <b>true</b></i>", defaultValue: true)		
    }
}

// Parse incoming device messages to generate events

def parse(String description) {
    checkDriverVersion()
    //log.debug "${device.displayName} Parsing '${description}'"
    def descMap = [:]
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    }
    catch (e) {
        if (settings?.logEnable) log.warn "${device.displayName} exception caught while parsing description ${description} \r descMap:  ${descMap}"
        return null
    }
    logDebug "Parsed descMap: ${descMap} (description:${description})"

    Map map = null // [:]

    if (descMap.attrId != null) {
        //log.trace "parsing descMap.attrId ${descMap.attrId}"
        parseAttributes(descMap)
        return
    }
    else if (descMap?.clusterId == "0013" && descMap?.profileId != null && descMap?.profileId == "0000") {
        logInfo "device model ${device.data.model}, manufacturer ${device.data.manufacturer} <b>re-joined the network</b> (deviceNetworkId ${device.properties.deviceNetworkId}, zigbeeId ${device.properties.zigbeeId})"
    } else {
        logDebug "${device.displayName} unprocessed EP: ${descMap.sourceEndpoint} cluster: ${descMap.clusterId} attrId: ${descMap.attrId}"
    }
}

def parseAttributes(Map descMap) {
    // attribute report received
    List attrData = [[cluster: descMap.cluster, attrId: descMap.attrId, value: descMap.value, status: descMap.status]]
    descMap.additionalAttrs.each {
        attrData << [cluster: descMap.cluster, attrId: it.attrId, value: it.value, status: it.status]
    }
    //log.trace "attrData 2 = ${attrData} "
    attrData.each {
        parseSingleAttribute(it, descMap)
    } // for each attribute    
}

private void parseSingleAttribute(Map it, Map descMap) {
    //log.trace "parseSingleAttribute :${it}"
    if (it.status == "86") {
        log.warn "${device.displayName} Read attribute response: unsupported Attributte ${it.attrId} cluster ${descMap.cluster}"
        return
    }
    switch (it.cluster) {
        case "0000":
            parseBasicClusterAttribute(it)
            break
        case "0006":
            //log.warn "case cluster 0006"
            switch (it.attrId) {
                case "0000":
                    //log.warn "case cluster 0006 attrId 0000"
                    processOnOff(it, descMap)
                    break
                default:
                    //log.warn "case cluster 0006 attrId ${it.attrId}"
                    processOnOfClusterOtherAttr(it)
                    break
            }
            break
        case "0008":
            if (logEnable) log.warn "${device.displayName} may be a dimmer? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "0300":
            if (logEnable) log.warn "${device.displayName} may be a bulb? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "0702":
        case "0B04":
            if (logEnable) log.warn "${device.displayName} may be a power monitoring socket? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "E000":
        case "E001":
            processOnOfClusterOtherAttr(it)
            break
        case "EF00": // Tuya cluster
            log.warn "${device.displayName} NOT PROCESSED Tuya Cluster EF00 attribute ${it.attrId}\n descMap = ${descMap}"
            break
        case "FFFD": // TuyaClusterRevision
            if (logEnable) log.warn "${device.displayName}  Tuya Cluster Revision cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "FFFE": // AttributeReportingStatus
            if (logEnable) log.warn "${device.displayName}  Tuya Attribute Reporting Status cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        default:
            if (logEnable) {
                String respType = (command == "0A") ? "reportResponse" : "readAttributeResponse"
                log.warn "${device.displayName} parseAttributes: <b>NOT PROCESSED</b>: <b>cluster ${descMap.cluster}</b> attribite:${it.attrId}, value:${it.value}, encoding:${it.encoding}, respType:${respType}"
            }
            break
    } // it.cluster
}

def parseBasicClusterAttribute(Map it) {
    // https://github.com/zigbeefordomoticz/Domoticz-Zigbee/blob/6df64ab4656b65ec1a450bd063f71a350c18c92e/Modules/readClusters.py 
    switch (it.attrId) {
        case "0000":
            logDebug "ZLC version: ${it.value}"        // default 0x03
            break
        case "0001":
            logDebug "Applicaiton version: ${it.value}"    // For example, 0b 01 00 0001 = 1.0.1, where 0x41 is 1.0.1
            break                                                            // https://developer.tuya.com/en/docs/iot-device-dev/tuya-zigbee-lighting-dimmer-swith-access-standard?id=K9ik6zvlvbqyw
        case "0002":
            logDebug "Stack version: ${it.value}"        // default 0x02
            break
        case "0003":
            logDebug "HW version: ${it.value}"        // default 0x01
            break
        case "0004":
            logDebug "Manufacturer name: ${it.value}"
            break
        case "0005":
            logDebug "Model Identifier: ${it.value}"
            break
        case "0007":
            logDebug "Power Source: ${it.value}"        // enum8-0x30 default 0x03
            break
        case "4000":    //software build
            logDebug "softwareBuild: ${it.value}"
            //updateDataValue("$LAB softwareBuild",it.value ?: "unknown")
            break
        case "FFE2":
        case "FFE4":
            logDebug "Attribite ${it.attrId} : ${it.value}"
            break
        case "FFFD":    // Cluster Revision (Tuya specific)
            logDebug "Cluster Revision 0xFFFD: ${it.value}"    //uint16 -0x21 default 0x0001
            break
        case "FFFE":    // Tuya specific
            logDebug "Tuya specific 0xFFFE: ${it.value}"
            break
        default:
            if (logEnable) log.warn "${device.displayName} parseBasicClusterAttribute cluster:${cluster} UNKNOWN  attrId ${it.attrId} value:${it.value}"
    }
}

def processOnOff(it, descMap) {
    // descMap.command =="0A" - switch toggled physically
    // descMap.command =="01" - get switch status
    // descMap.command =="0B" - command response
    def cd = getChildDevice("${device.id}-${descMap.endpoint}")
    if (cd == null) {
        if (!(device.data.model in ['TS0011', 'TS0001'])) {
            log.warn "${device.displayName} Child device ${device.id}-${descMap.endpoint} not found. Initialise parent device first"
            return
        }
    }
    def switchAttribute = descMap.value == "01" ? "on" : "off"
    if (cd != null) {
        if (descMap.command in ["0A", "0B"]) {
            // switch toggled
            cd.parse([[name: "switch", value: switchAttribute, descriptionText: "Child switch ${descMap.endpoint} turned $switchAttribute"]])
        } else if (descMap.command == "01") {
            // report switch status
            cd.parse([[name: "switch", value: switchAttribute, descriptionText: "Child switch  ${descMap.endpoint} is $switchAttribute"]])
        }
    }
    if (switchAttribute == "on") {
        logDebug "Parent switch on"
        sendEvent(name: "switch", value: "on")
        return
    } else if (switchAttribute == "off") {
        def cdsOn = 0
        // cound number of switches on
        getChildDevices().each { child ->
            if (getChildId(child) != descMap.endpoint && child.currentValue('switch') == "on") {
                cdsOn++
            }
        }
        if (cdsOn == 0) {
            logDebug "Parent switch off"
            sendEvent(name: "switch", value: "off")
            return
        }
    }
}

def off() {
    if (settings?.txtEnable) log.info "${device.displayName} Turning all child switches off"
    "he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x0 {}"
}

def on() {
    if (settings?.txtEnable) log.info "${device.displayName} Turning all child switches on"
    "he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x1 {}"
}

def refresh() {
    logDebug "refreshing"
    "he rattr 0x${device.deviceNetworkId} 0xFF 0x0006 0x0"
}

def ping() {
    refresh()
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex, 16)
}

private String getChildId(childDevice) {
    return childDevice.deviceNetworkId.substring(childDevice.deviceNetworkId.length() - 2)
}

def componentOn(childDevice) {
    logDebug "sending componentOn ${childDevice.deviceNetworkId}"
    sendHubCommand(new HubAction("he cmd 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x1 {}", Protocol.ZIGBEE))
}

def componentOff(childDevice) {
    logDebug "sending componentOff ${childDevice.deviceNetworkId}"
    sendHubCommand(new HubAction("he cmd 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x0 {}", Protocol.ZIGBEE))
}

def componentRefresh(childDevice) {
    logDebug "sending componentRefresh ${childDevice.deviceNetworkId} ${childDevice}"
    sendHubCommand(new HubAction("he rattr 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x0", Protocol.ZIGBEE))
}

def setupChildDevices() {
    logDebug "Parent setupChildDevices"
    deleteObsoleteChildren()
    def buttons = 0
    switch (device.data.model) {
        case 'SBM300Z6':
            buttons = 6
            break
        case 'TS011F':
            if (device.data.manufacturer == '_TZ3000_zmy1waw6') {
                buttons = 1
                break
            } else {
                // continue below
            }
        case 'TS0115':
        case 'SBM300Z5':
            buttons = 5
            break
        case 'TS0004':
        case 'TS0014':
        case 'SBM300Z4':
            buttons = 4
            break
        case 'TS0003':
        case 'TS0013':
        case 'SBM300Z3':
        case 'ISM300Z3':
            buttons = 3
            break
        case 'TS0002':
        case 'TS0012':
        case 'SBM300Z2':
        case 'SIN-4-2-20':
        case 'SIN-4-2-20_PRO':
            buttons = 2
            break
        case 'TS0011':
        case 'TS0001':
            buttons = 0
            break
        default:
            break
    }
    logDebug "model: ${device.data.model} buttons: $buttons"
    createChildDevices((int) buttons)
}

def createChildDevices(int buttons) {
    logDebug "Parent createChildDevices"

    if (buttons == 0)
        return

    for (i in 1..buttons) {
        def childId = "${device.id}-0${i}"
        def existingChild = getChildDevices()?.find { it.deviceNetworkId == childId }

        if (existingChild) {
            log.info "${device.displayName} Child device ${childId} already exists (${existingChild})"
        } else {
            log.info "${device.displayName} Creatung device ${childId}"
            addChildDevice("hubitat", "Generic Component Switch", childId, [isComponent: true, name: "Switch EP0${i}", label: "${device.displayName} EP0${i}"])
        }
    }
}

def deleteObsoleteChildren() {
    logDebug "Parent deleteChildren"

    getChildDevices().each { child ->
        if (!child.deviceNetworkId.startsWith(device.id) || child.deviceNetworkId == "${device.id}-00") {
            log.info "${device.displayName} Deleting ${child.deviceNetworkId}"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

def driverVersionAndTimeStamp() { version() + ' ' + timeStamp() }

def checkDriverVersion() {
    if (state.driverVersion == null || (driverVersionAndTimeStamp() != state.driverVersion)) {
        if (txtEnable == true) log.debug "${device.displayName} updating the settings from the current driver ${device.properties.typeName} version ${state.driverVersion} to the new version ${driverVersionAndTimeStamp()} [model ${device.data.model}, manufacturer ${device.data.manufacturer}, application ${device.data.application}, endpointId ${device.endpointId}]"
        initializeVars(fullInit = false)
        state.driverVersion = driverVersionAndTimeStamp()
    }
}

void initializeVars(boolean fullInit = true) {
    if (settings?.txtEnable) log.info "${device.displayName} InitializeVars()... fullInit = ${fullInit}"
    if (fullInit == true) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
    }
    if (settings?.logEnable == null) device.updateSetting("logEnable", true)
    if (settings?.txtEnable == null) device.updateSetting("txtEnable", true)
}

def initialize() {
    logDebug "Initializing..."
    initializeVars(fullInit = true)
    configure()    // added 11/12/2022
    setupChildDevices()
}

def installed() {
    logInfo "<b>Parent installed</b>, typeName ${device.properties.typeName}, version ${driverVersionAndTimeStamp()}, deviceNetworkId ${device.properties.deviceNetworkId}, zigbeeId ${device.properties.zigbeeId}"
    logInfo "model ${device.data.model}, manufacturer ${device.data.manufacturer}, application ${device.data.application}, endpointId ${device.endpointId}"
}

def updated() {
    logDebug "Parent updated"
}


def configure() {
    logDebug " configure().."
    List<String> cmds = []
    if (device.data.manufacturer in ["_TZ3000_cfnprab5", "_TZ3000_okaz9tjs"]) {
        log.warn "this device ${device.data.manufacturer} is known to NOT work with HE!"
    }
    
    if (noBindingButPolling()) {
        //these  will send out device anounce message at ervery 2 mins as heart beat, setting 0x0099 to 1 will disable it.
        cmds += zigbee.writeAttribute(zigbee.BASIC_CLUSTER, 0x0099, 0x20, 0x01, [mfgCode: 0x0000])
        // Hack : Need to disable reporting for thoses devices, else It will enable a auto power off after 2mn.     // see https://github.com/dresden-elektronik/deconz-rest-plugin/issues/3693
        // https://github.com/Mariano-Github/Edge-Drivers-Beta/blob/652bcfbcf7b8ab8a14557e097b740216760f2b02/zigbee-multi-switch-v4-childs/src/init.lua 
        log.warn "disabling ${device.data.manufacturer} device announce message every 2 mins and skipping reporting configuiration!"
        cmds += zigbee.onOffRefresh()
    } else {
        //cmds += refresh()
        cmds += zigbee.onOffConfig()
        cmds += zigbee.onOffRefresh()
    }
    sendZigbeeCommands(cmds)
}

void sendZigbeeCommands(List<String> cmds) {
    logDebug "sendZigbeeCommands : ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}


def logDebug(msg) {
    String sDnMsg = device?.displayName + " " + msg
    if (settings?.logEnable) log.debug sDnMsg
}

def logInfo(msg) {
    String sDnMsg = device?.displayName + " " + msg
    if (settings?.txtEnable) log.info sDnMsg
}

def powerOnState(relayMode) {
    List<String> cmds = []
    int modeEnum = 99
    switch (relayMode) {
        case "OFF":
            modeEnum = 0
            break
        case "ON":
            modeEnum = 1
            break
        case "Last state":
            modeEnum = 2
            break
        default:
            log.error "${device.displayName} please select a Power On State option"
            return
    }
    logDebug("setting  Power On State option to: ${relayMode}  (${modeEnum}")
    cmds += zigbee.writeAttribute(0x0006, 0x8002, DataType.ENUM8, modeEnum)
    sendZigbeeCommands(cmds)
}

def switchType(type) {
    List<String> cmds = []
    int modeEnum = 99
    switch (type) {
        case "toggle":
            modeEnum = 0
            break
        case "state":
            modeEnum = 1
            break
        case "momentary":
            modeEnum = 2
            break
        default:
            log.error "${device.displayName} please select a Switch Type"
            return
    }
    logDebug("setting  Switch Type to: ${type} (${modeEnum})")
    cmds += zigbee.writeAttribute(0xE001, 0xD030, DataType.ENUM8, modeEnum)
    sendZigbeeCommands(cmds)
}

def processOnOfClusterOtherAttr(Map it) {
    //logDebug "processOnOfClusterOtherAttr attribute ${it.attrId} value=${it.value}"
    def mode
    def attrName
    def value
    try {
        value = it.value as int
    }
    catch (e) {
        value = it.value
    }
    def clusterPlusAttr = it.cluster + "_" + it.attrId
    //log.trace "clusterPlusAttr = ${clusterPlusAttr}"
    switch (clusterPlusAttr) {
        case "0006_4001":
        case "0006_4002":
            attrName = "attribute " + clusterPlusAttr
            mode = value.toString()
            break
        case "0006_8000":
            attrName = "Child Lock"
            mode = value == 0 ? "off" : "on"
            break
        case "0006_8002":
            attrName = "Power On State"
            mode = value == 0 ? "off" : value == 1 ? "on" : value == 2 ? "Last state" : null
            break
        case "E000_D001":
        case "E000_D002":
        case "E000_D003":
            attrName = "attribute " + clusterPlusAttr
            mode = value.toString()
            break
        case "E001_D030":
            attrName = "Switch Type"
            mode = value == 0 ? "toggle" : value == 1 ? "state" : value == 2 ? "momentary state" : null
            break
        default:
            logDebug "processOnOfClusterOtherAttr: <b>UNPROCESSED On/Off Cluster</b>  attrId: ${it.attrId} value: ${it.value}"
            return
    }
    if (txtEnable) log.info "${device.displayName} ${attrName} is: ${mode} (${value})"
}
