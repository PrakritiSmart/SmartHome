/**
 *  OWON Occupancy driver for Hubitat
 *
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 * Copied from kkossev's driver for Tuya Multi Sensor 4 in 1 code
 * Hosted at https://raw.githubusercontent.com/kkossev/Hubitat/development/Drivers/Tuya%20Multi%20Sensor%204%20In%201/Tuya%20Multi%20Sensor%204%20In%201.groovy
 * 
*/

def version() { "1.0.11" }
def timeStamp() {"2022/08/22 9:48 PM"}

import groovy.json.*
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import hubitat.device.HubAction
import hubitat.device.Protocol
import hubitat.zigbee.clusters.iaszone.ZoneStatus

@Field static final Boolean debug = false

metadata {
    definition (name: "OWON Occupancy Sensor", namespace: "prakitismart", author: "Prakiti Smart", importUrl: "https://raw.githubusercontent.com/PrakitiSmart/SmartHome/main/Hubitat/Drivers/OWON%20Occupancy%20Sensor.groovy", singleThreaded: true ) {
        capability "Sensor"
        capability "MotionSensor"
        capability "TamperAlert"
        capability "PowerSource"
        capability "Refresh"

        
        command "configure", [[name: "Configure the sensor after switching drivers"]]
        command "initialize", [[name: "Initialize the sensor after switching drivers.  \n\r   ***** Will load device default values! *****" ]]
        command "refresh",   [[name: "May work for some DC/mains powered sensors only"]] 

        if (debug == true) {
            command "testX"
        }
        
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0406",      outClusters:"0003",                model:"OCP305", manufacturer:"OWON"            // 
    }
    
    preferences {

            input (name: "logEnable", type: "bool", title: "Debug logging", description: "<i>Debug information, useful for troubleshooting. Recommended value is <b>false</b></i>", defaultValue: true)
            input (name: "txtEnable", type: "bool", title: "Description text logging", description: "<i>Display sensor states in HE log page. Recommended value is <b>true</b></i>", defaultValue: true)		
    }
}

@Field static final Integer numberOfconfigParams = 11
@Field static final Integer temperatureOffsetParamIndex = 0
@Field static final Integer humidityOffsetParamIndex = 1
@Field static final Integer luxOffsetParamIndex = 2
@Field static final Integer ledEnableParamIndex = 3
@Field static final Integer sensitivityParamIndex = 4
@Field static final Integer detectionDelayParamIndex = 5
@Field static final Integer fadingTimeParamIndex = 6
@Field static final Integer minimumDistanceParamIndex = 7
@Field static final Integer maximumDistanceParamIndex = 8
@Field static final Integer keepTimeParamIndex = 9
@Field static final Integer radarSensitivityParamIndex = 10


@Field static final Integer presenceCountTreshold = 4
@Field static final Integer defaultPollingInterval = 3600

def isOWONOCP305Radar() { return device.getDataValue('manufacturer') in ['OWON'] }


private getSETDATA()            { 0x00 }
private getSETTIME()            { 0x24 }

// tuya DP type
private getDP_TYPE_RAW()        { "01" }    // [ bytes ]
private getDP_TYPE_BOOL()       { "01" }    // [ 0/1 ]
private getDP_TYPE_VALUE()      { "02" }    // [ 4 byte value ]
private getDP_TYPE_STRING()     { "03" }    // [ N byte string ]
private getDP_TYPE_ENUM()       { "04" }    // [ 0-255 ]
private getDP_TYPE_BITMAP()     { "05" }    // [ 1,2,4 bytes ] as bits

// Parse incoming device messages to generate events
def parse(String description) {
    checkDriverVersion()
    if (state.rxCounter != null) state.rxCounter = state.rxCounter + 1
    setPresent()
    if (settings?.logEnable) log.debug "${device.displayName} parse(${device.getDataValue('manufacturer')}) descMap = ${zigbee.parseDescriptionAsMap(description)}"
    if (description?.startsWith('enroll request')) {
         /* The Zone Enroll Request command is generated when a device embodying the Zone server cluster wishes to be  enrolled as an active  alarm device. It  must do this immediately it has joined the network  (during commissioning). */
        if (settings?.logEnable) log.info "${device.displayName} Sending IAS enroll response..."
        ArrayList<String> cmds = zigbee.enrollResponse() + zigbee.readAttribute(0x0500, 0x0000)
        if (settings?.logEnable) log.debug "${device.displayName} enroll response: ${cmds}"
        sendZigbeeCommands( cmds )  
    }    
    else if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap.cluster == "0406" && descMap.attrId == "0000") {    // OWON
            def raw = Integer.parseInt(descMap.value,16)
            handleMotion( raw & 0x01 )
		}
        else if (descMap.profileId == "0000") {    // zdo
            parseZDOcommand(descMap)
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0001") {
            if (settings?.logEnable) log.info "${device.displayName} check-in (application version is ${descMap?.value})"
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0004") {
            if (settings?.logEnable) log.info "${device.displayName} device manufacturer is ${descMap?.value}"
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0007") {
            def value = descMap?.value == "00" ? "battery" : descMap?.value == "01" ? "mains" : descMap?.value == "03" ? "battery" : descMap?.value == "04" ? "dc" : "unknown" 
            if (settings?.logEnable) log.info "${device.displayName} reported Power source ${descMap?.value}"
            if (isOWONOCP305Radar) {     // for radars force powerSource 'dc'
                powerSourceEvent( value )
            }
        } 
                 
        else if (descMap?.command == "00" && descMap?.clusterId == "8021" ) {    // bind response
            if (settings?.logEnable) log.debug "${device.displayName }bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1]=="00" ? 'Success' : '<b>FAILURE</b>'})"
        } 
        else {
            if (settings?.logEnable) log.debug "${device.displayName} <b> NOT PARSED </b> : descMap = ${descMap}"
        }
    } // if 'catchall:' or 'read attr -'
    else {
        if (settings?.logEnable) log.debug "${device.displayName} <b> UNPROCESSED </b> description = ${description} descMap = ${zigbee.parseDescriptionAsMap(description)}"
    }
}


def parseZDOcommand( Map descMap ) {
    switch (descMap.clusterId) {
        case "0006" :
            if (settings?.logEnable) log.info "${device.displayName} Received match descriptor request, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Input cluster count:${descMap.data[5]} Input cluster: 0x${descMap.data[7]+descMap.data[6]})"
            break
        case "0013" : // device announcement
            if (settings?.logEnable) log.info "${device.displayName} Received device announcement, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Device network ID: ${descMap.data[2]+descMap.data[1]}, Capability Information: ${descMap.data[11]})"
            break
        case "8004" : // simple descriptor response
            if (settings?.logEnable) log.info "${device.displayName} Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, lenght:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}"
            //parseSimpleDescriptorResponse( descMap )
            break
        case "8005" : // endpoint response
            def endpointCount = descMap.data[4]
            def endpointList = descMap.data[5]
            if (settings?.logEnable) log.info "${device.displayName} zdo command: cluster: ${descMap.clusterId} (endpoint response) endpointCount = ${endpointCount}  endpointList = ${endpointList}"
            break
        case "8021" : // bind response
            if (settings?.logEnable) log.info "${device.displayName} Received bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1]=="00" ? 'Success' : '<b>Failure</b>'})"
            break
        case "8022" : //unbind request
            if (settings?.logEnable) log.info "${device.displayName} zdo command: cluster: ${descMap.clusterId} (unbind request)"
            break
        case "8034" : //leave response
            if (settings?.logEnable) log.info "${device.displayName} zdo command: cluster: ${descMap.clusterId} (leave response)"
            break
        default :
            if (settings?.logEnable) log.warn "${device.displayName} Unprocessed ZDO command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    }
}
 

private handleMotion( motionActive, isDigital=false ) {    
    if (motionActive) {
        def timeout = motionResetTimer ?: 0
        // If the sensor only sends a motion detected message, the reset to motion inactive must be  performed in code
        if (motionReset == true && timeout != 0) {
            runIn(timeout, resetToMotionInactive, [overwrite: true])
        }
        if (device.currentState('motion')?.value != "active") {
            state.motionStarted = now()
        }
    }
    else {
        if (device.currentState('motion')?.value == "inactive") {
            if (settings?.logEnable) log.debug "${device.displayName} ignored motion inactive event after ${getSecondsInactive()}s"
            return [:]   // do not process a second motion inactive event!
        }
    }
	return getMotionResult(motionActive, isDigital)
}

def getMotionResult( motionActive, isDigital=false ) {
	def descriptionText = "Detected motion"
    if (!motionActive) {
		descriptionText = "Motion reset to inactive after ${getSecondsInactive()}s"
    }
    else {
        descriptionText = device.currentValue("motion") == "active" ? "Motion is active ${getSecondsInactive()}s" : "Detected motion"
    }
    
    if (txtEnable) log.info "${device.displayName} ${descriptionText}"
	sendEvent (
			name			: 'motion',
			value			: motionActive ? 'active' : 'inactive',
            //isStateChange   : true,
            type            : isDigital == true ? "digital" : "physical",
			descriptionText : descriptionText
	)
}

def resetToMotionInactive() {
	if (device.currentState('motion')?.value == "active") {
		def descText = "Motion reset to inactive after ${getSecondsInactive()}s (software timeout)"
		sendEvent(
			name : "motion",
			value : "inactive",
			isStateChange : true,
            type:  "digital",
			descriptionText : descText
		)
        if (txtEnable) log.info "${device.displayName} ${descText}"
	}
    else {
        if (txtEnable) log.debug "${device.displayName} ignored resetToMotionInactive (software timeout) after ${getSecondsInactive()}s"
    }
}

def getSecondsInactive() {
    if (state.motionStarted) {
        return Math.round((now() - state.motionStarted)/1000)
    } else {
        return motionResetTimer ?: 0
    }
}

def powerSourceEvent( state = null) {
    if (state != null && state == 'unknown' ) {
        sendEvent(name : "powerSource",	value : "unknown", descriptionText: "device is OFFLINE", type: "digital")
    }
    else if (state != null ) {
        sendEvent(name : "powerSource",	value : state, descriptionText: "device is back online", type: "digital")
    }
    else {
        if (isOWONOCP305Radar()) {
            sendEvent(name : "powerSource",	value : "dc", descriptionText: "device is back online", type: "digital")
        }
        else {
            sendEvent(name : "powerSource",	value : "battery", descriptionText: "device is back online", type: "digital")
        }
    }
}

// called on initial install of device during discovery
// also called from initialize() in this driver!
def installed() {
    log.info "${device.displayName} installed()"
    unschedule()
}

// called when preferences are saved
def updated() {
    checkDriverVersion()
    ArrayList<String> cmds = []
    
    if (settings?.txtEnable) log.info "${device.displayName} Updating ${device.getLabel()} (${device.getName()}) model ${device.getDataValue('model')} manufacturer <b>${device.getDataValue('manufacturer')}</b>"
    if (settings?.txtEnable) log.info "${device.displayName} Debug logging is <b>${logEnable}</b>; Description text logging is <b>${txtEnable}</b>"
    if (logEnable==true) {
        runIn(86400, logsOff)    // turn off debug logging after 24 hours
        if (settings?.txtEnable) log.info "${device.displayName} Debug logging is will be turned off after 24 hours"
    }
    else {
        unschedule(logsOff)
    }
    
    
    if (true /*state.hashStringPars != calcParsHashString()*/) {    // an configurable device parameter was changed
        if (settings?.logEnable) log.debug "${device.displayName} Config parameters changed! old=${state.hashStringPars} new=${calcParsHashString()}"
        
        //
        state.hashStringPars = calcParsHashString()
    }
    else {
        if (settings?.logEnable) log.debug "${device.displayName} no change in state.hashStringPars = {state.hashStringPars}"
    }
    
    if (cmds != null) {
        if (settings?.logEnable) log.debug "${device.displayName} sending the changed AdvancedOptions"
        sendZigbeeCommands( cmds )  
    }
    if (settings?.txtEnable) log.info "${device.displayName} preferences updates are sent to the device..."
}


def refresh() {
    ArrayList<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0007, 0xfffe], [:], delay=200)     // Power Source, attributeReportingStatus
    if (settings?.logEnable)  {log.debug "${device.displayName} refresh()..."}
    sendZigbeeCommands( cmds ) 
}

def driverVersionAndTimeStamp() {version()+' '+timeStamp()}

def checkDriverVersion() {
    if (state.driverVersion != null && driverVersionAndTimeStamp() == state.driverVersion) {
        // no driver version change
    }
    else {
        if (txtEnable==true) log.debug "${device.displayName} updating the settings from the current driver version ${state.driverVersion} to the new version ${driverVersionAndTimeStamp()}"
        initializeVars( fullInit = false ) 
        state.driverVersion = driverVersionAndTimeStamp()
        if (state.lastPresenceState != null) {
            state.remove('lastPresenceState')    // removed in version 1.0.6 
        }
    }
}

def logInitializeRezults() {
    if (settings?.txtEnable) log.info "${device.displayName} manufacturer  = ${device.getDataValue("manufacturer")}"
    if (settings?.txtEnable) log.info "${device.displayName} Initialization finished\r                          version=${version()} (Timestamp: ${timeStamp()})"
}

// called by initialize() button
void initializeVars( boolean fullInit = false ) {
    if (settings?.txtEnable) log.info "${device.displayName} InitializeVars( fullInit = ${fullInit} )..."
    if (fullInit == true ) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
        state.motionStarted = now()
    }
    //
    state.packetID = 0
    state.rxCounter = 0
    state.txCounter = 0
    if (fullInit == true || state.notPresentCounter == null) state.notPresentCounter = 0
    if (!isOWONOCP305Radar()) {
        if (state.lastBattery == null) state.lastBattery = "0"
    }
    //
    if (fullInit == true || settings.logEnable == null) device.updateSetting("logEnable", true)
    if (fullInit == true || settings.txtEnable == null) device.updateSetting("txtEnable", true)
    if (fullInit == true || settings.motionReset == null) device.updateSetting("motionReset", false)
    if (fullInit == true || settings.motionResetTimer == null) device.updateSetting("motionResetTimer", 60)

    //
    if (fullInit == true) sendEvent(name : "powerSource",	value : "?", isStateChange : true)
    //
    state.hashStringPars = calcParsHashString()
    if (settings?.logEnable) log.debug "${device.displayName} state.hashStringPars = ${state.hashStringPars}"
}

// called when used with capability "Configuration" is called when the configure button is pressed on the device page. 
// Runs when driver is installed, after installed() is run. if capability Configuration exists, a Configure command is added to the ui
// It is also called on initial install after discovery.
def configure() {
    if (settings?.txtEnable) log.info "${device.displayName} configure().."
    runIn( defaultPollingInterval, pollPresence, [overwrite: true])
    state.motionStarted = now()
    ArrayList<String> cmds = []
   
    if (isOWONOCP305Radar()) {    // skip the binding for all the radars!
        cmds += "delay 200"
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x02 0x01 0x0402 {${device.zigbeeId}} {}"
        cmds += "delay 200"
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x02 0x01 0x0405 {${device.zigbeeId}} {}"
        cmds += "delay 200"
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x03 0x01 0x0400 {${device.zigbeeId}} {}"
    }

    sendZigbeeCommands(cmds)    
}

// called when used with capability "Initialize" it will call this method every time the hub boots up. So for things that need refreshing or re-connecting (LAN integrations come to mind here) ..
// runs first time driver loads, ie system startup 
// when capability Initialize exists, a Initialize command is added to the ui.
def initialize( boolean fullInit = true ) {
    log.info "${device.displayName} Initialize( fullInit = ${fullInit} )..."
    unschedule()
    initializeVars( fullInit )
    installed()
    configure()
    runIn( 1, updated, [overwrite: true])
    runIn( 3, logInitializeRezults, [overwrite: true])
}


Integer safeToInt(val, Integer defaultVal=0) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

Double safeToDouble(val, Double defaultVal=0.0) {
	return "${val}"?.isDouble() ? "${val}".toDouble() : defaultVal
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    if (settings?.logEnable) {log.debug "${device.displayName} <b>sendZigbeeCommands</b> (cmd=$cmd)"}
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
            allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
            if (state.txCounter != null) state.txCounter = state.txCounter + 1
    }
    sendHubCommand(allActions)
}

private getPACKET_ID() {
    state.packetID = ((state.packetID ?: 0) + 1 ) % 65536
    return zigbee.convertToHexString(state.packetID, 4)
}

private getDescriptionText(msg) {
	def descriptionText = "${device.displayName} ${msg}"
	if (settings?.txtEnable) log.info "${descriptionText}"
	return descriptionText
}

def logsOff(){
    if (settings?.logEnable) log.info "${device.displayName} debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}


def sendBatteryEvent( roundedPct, isDigital=false ) {
    if (roundedPct > 100) roundedPct = 100
    if (roundedPct < 0)   roundedPct = 0
    sendEvent(name: 'battery', value: roundedPct, unit: "%", type:  isDigital == true ? "digital" : "physical", isStateChange: true )    
}


import java.security.MessageDigest
String generateMD5(String s) {
    if(s != null) {
        return MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
    } else {
        return "null"
    }
}


def calcParsHashString() {
    String hashPars = ''
    for (int i = 0; i< numberOfconfigParams; i++) {
        hashPars += calcHashParam( i )     
    }
    return hashPars
}

def getHashParam(num) {
    try {
        return state.hashStringPars[num*2..num*2+1]
    }
    catch (e) {
        log.error "exception caught getHashParam(${num})"
        return '??' 
    }
}


def calcHashParam(num) {
    def hashByte
    try {
        switch (num) {
            case temperatureOffsetParamIndex : hashByte = generateMD5(temperatureOffset.toString())[-2..-1];  break
            case humidityOffsetParamIndex :    hashByte = generateMD5(humidityOffset.toString())[-2..-1];     break
            case luxOffsetParamIndex :         hashByte = generateMD5(luxOffset.toString())[-2..-1];          break
            case ledEnableParamIndex :         hashByte = generateMD5(ledEnable.toString())[-2..-1];          break
            case sensitivityParamIndex :       hashByte = generateMD5(sensitivity.toString())[-2..-1];        break
            case detectionDelayParamIndex :    hashByte = generateMD5(detectionDelay.toString())[-2..-1];     break
            case fadingTimeParamIndex :        hashByte = generateMD5(fadingTime.toString())[-2..-1];         break
            case minimumDistanceParamIndex :   hashByte = generateMD5(minimumDistance.toString())[-2..-1];    break
            case maximumDistanceParamIndex :   hashByte = generateMD5(maximumDistance.toString())[-2..-1];    break
            case keepTimeParamIndex :          hashByte = generateMD5(keepTime.toString())[-2..-1];           break
            case radarSensitivityParamIndex :  hashByte = generateMD5(radarSensitiviry.toString())[-2..-1];   break
            //minimumDistance
            default :
                log.error "invalid par calcHashParam(${num})"
                return '??'
        }
    }
    catch (e) {
        log.error "exception caught calcHashParam(${num})"
        return '??' 
    }
}

def testX() {
    /*
    //sendSensitivity("high")
    def str = getSensitivityString(2)
    log.trace "str = ${str}"
//    device.updateSetting("sensitivity", [value:"No selection", type:"enum"])
    device.updateSetting("sensitivity", [value:str, type:"enum"])
*/
    
    def str = "Off"
    log.trace "str = ${str}"
//    device.updateSetting("sensitivity", [value:"No selection", type:"enum"])
    // "testType", [type:"text", value: "auto"]
    device.updateSetting("ledStatusAIR", [type:"enum", value: 1.toString()])
    
}

def getSensitivityString( value ) { return value == 0 ? "low" : value == 1 ? "medium" : value == 2 ? "high" : null }
def getSensitivityValue( str )    { return str == "low" ? 0: str == "medium" ? 1 : str == "high" ? 02 : null }

def getKeepTimeString( value )    { return value == 0 ? "30" : value == 1 ? "60" : value == 2 ? "120" : null }
def getKeepTimeValue( str )       { return  str == "30" ? 0: str == "60" ? 1 : str == "120" ? 02 : str == "240" ? 03 : null }

def readSensitivity()  { return zigbee.readAttribute(0x0500, 0x0013, [:], delay=200) }
def readKeepTime()     { return zigbee.readAttribute(0x0500, 0xF001, [:], delay=200) }

def sendSensitivity( String mode ) {
    if (mode == null) {
        if (settings?.logEnable) log.warn "${device.displayName} sensitivity is not set for ${device.getDataValue('manufacturer')}"
        return null
    }
    ArrayList<String> cmds = []
    String value = null
    if (!(is2in1() || isConfigurable()))  {
        if (settings?.logEnable) log.warn "${device.displayName} sensitivity configuration may not work for ${device.getDataValue('manufacturer')}"
        // continue anyway ..
    }
    value = mode == "low" ? 0: mode == "medium" ? 1 : mode == "high" ? 02 : null
    if (value != null) {
        cmds += zigbee.writeAttribute(0x0500, 0x0013, DataType.UINT8, value.toInteger(), [:], delay=200)
        if (settings?.logEnable) log.debug "${device.displayName} sending sensitivity : ${mode} (${value.toInteger()})"
        //sendZigbeeCommands( cmds )         // only prepare the cmds here!
    }
    else {
        if (settings?.logEnable) log.warn "${device.displayName} sensitivity ${mode} is not supported for your model:${device.getDataValue('model') } manufacturer:${device.getDataValue('manufacturer')}"
    }
    return cmds
}

def sendKeepTime( String mode ) {
    if (mode == null) {
        if (settings?.logEnable) log.warn "${device.displayName} Keep Time is not set for ${device.getDataValue('manufacturer')}"
        return null
    }
    ArrayList<String> cmds = []
    String value = null
    if (!(is2in1() || isConfigurable()))  {
        if (settings?.logEnable) log.warn "${device.displayName} Keep Time configuration may not work for ${device.getDataValue('manufacturer')}"
        // continue anyway .. //['30':'30', '60':'60', '120':'120']
    }
    value = mode == "30" ? 0: mode == "60" ? 1 : mode == "120" ? 02 : null
    if (value != null) {
        cmds += zigbee.writeAttribute(0x0500, 0xF001, DataType.UINT8, value.toInteger(), [:], delay=200) 
        if (settings?.logEnable) log.debug "${device.displayName} sending sensitivity : ${mode} (${value.toInteger()})"     // only prepare the cmds here!
    }
    else {
        if (settings?.logEnable) log.warn "${device.displayName} Keep Time ${mode} is not supported for your model:${device.getDataValue('model') } manufacturer:${device.getDataValue('manufacturer')}"
    }
    return cmds
}


// called when any event was received from the Zigbee device in parse() method..
def setPresent() {
    powerSourceEvent()
    if (device.currentValue('powerSource', true) in ['unknown', '?']) {
        if (settings?.txtEnable) log.info "${device.displayName} is present"
        //log.trace "device.currentValue('battery', true) = ${device.currentValue('battery', true)}"
        if (!isOWONOCP305Radar()) {
            if (device.currentValue('battery', true) == 0 ) {
                if (state.lastBattery != null &&  safeToInt(state.lastBattery) != 0) {
                    //log.trace "restoring battery level to ${safeToInt(state.lastBattery)}"
                    sendBatteryEvent(safeToInt(state.lastBattery), isDigital=true)
                }
            }
        }
    }    
    state.notPresentCounter = 0    
}

// called every 60 minutes from pollPresence()
def checkIfNotPresent() {
    //log.trace "checkIfNotPresent()"
    if (state.notPresentCounter != null) {
        state.notPresentCounter = state.notPresentCounter + 1
        if (state.notPresentCounter >= presenceCountTreshold) {
            if (!(device.currentValue('powerSource', true) in ['unknown'])) {
    	        powerSourceEvent("unknown")
                if (settings?.txtEnable) log.warn "${device.displayName} is not present!"
            }
            if (!(device.currentValue('motion', true) in ['inactive', '?'])) {
                handleMotion(false, isDigital=true)
                if (settings?.txtEnable) log.warn "${device.displayName} forced motion to '<b>inactive</b>"
            }
            //log.trace "battery was ${safeToInt(device.currentValue('battery', true))}"
            if (safeToInt(device.currentValue('battery', true)) != 0) {
                if (settings?.txtEnable) log.warn "${device.displayName} forced battery to '<b>0 %</b>"
                sendBatteryEvent( 0, isDigital=true )
            }
        }
    }
    else {
        state.notPresentCounter = 0  
    }
}


// check for device offline every 60 minutes
def pollPresence() {
    if (logEnable) log.debug "${device.displayName} pollPresence()"
    checkIfNotPresent()
    runIn( defaultPollingInterval, pollPresence, [overwrite: true])
}



def deleteAllStatesAndJobs() {
    device.removeDataValue("softwareBuild")
    log.info "${device.displayName} jobs and states cleared. HE hub is ${getHubVersion()}, version is ${location.hub.firmwareVersionString}"
}


def test( dpCommand, dpValue, dpTypeString ) {
    ArrayList<String> cmds = []
    def dpType   = dpTypeString=="DP_TYPE_VALUE" ? DP_TYPE_VALUE : dpTypeString=="DP_TYPE_BOOL" ? DP_TYPE_BOOL : dpTypeString=="DP_TYPE_ENUM" ? DP_TYPE_ENUM : null
    def dpValHex = dpTypeString=="DP_TYPE_VALUE" ? zigbee.convertToHexString(dpValue as int, 8) : dpValue
    log.warn " sending TEST command=${dpCommand} value=${dpValue} ($dpValHex) type=${dpType}"
}    
