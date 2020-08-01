/**
 *  Copyright 2020 Markus Liljergren
 *
 *  Modifications Copyright 2020 Justin Walker
 *
 *  v0.5.0.0701 - original
 *  v0.6.0 - removed unused features (2020-07-12)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import hubitat.helper.HexUtils

metadata {
	definition (name: "Sonoff Zigbee Temperature & Humidity Sensor", namespace: "markusl", author: "Markus Liljergren") {
        capability "Battery"
        capability "Initialize"        
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"

        command "resetBatteryReplacedDate"

        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0402,0405,0001", outClusters:"0003", model:"TH01", manufacturer:"eWeLink", application:"04"
    }

    preferences {
        input(name: "debugLogging", type: "bool", title: styling_addTitleDiv("Enable debug logging"), description: ""  + styling_getDefaultCSS(), defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "infoLogging", type: "bool", title: styling_addTitleDiv("Enable info logging"), description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "lastCheckinEnable", type: "bool", title: styling_addTitleDiv("Enable Last Checkin"), description: styling_addDescriptionDiv("Records Date events if enabled"), defaultValue: true)
        input(name: "vMinSetting", type: "decimal", title: styling_addTitleDiv("Battery Minimum Voltage"), description: styling_addDescriptionDiv("Voltage when battery is considered to be at 0% (default = 2.5V)"), defaultValue: "2.5", range: "2.1..2.8")
        input(name: "vMaxSetting", type: "decimal", title: styling_addTitleDiv("Battery Maximum Voltage"), description: styling_addDescriptionDiv("Voltage when battery is considered to be at 100% (default = 3.0V)"), defaultValue: "3.0", range: "2.9..3.4")
        input(name: "tempUnitDisplayed", type: "enum", title: styling_addTitleDiv("Displayed Temperature Unit"), description: "", defaultValue: "1", required: true, multiple: false, options:[["1":"Celsius"], ["2":"Fahrenheit"], ["3":"Kelvin"]], displayDuringSetup: false)
        input(name: "tempOffset", type: "decimal", title: styling_addTitleDiv("Temperature Offset"), description: styling_addDescriptionDiv("Adjust the temperature by this many degrees."), displayDuringSetup: true, required: false, range: "*..*")
        input(name: "tempRes", type: "enum", title: styling_addTitleDiv("Temperature Resolution"), description: styling_addDescriptionDiv("Temperature sensor resolution (0..2 = maximum number of decimal places, default: 1)<br/>NOTE: If the 2nd decimal is a 0 (eg. 24.70) it will show without the last decimal (eg. 24.7)."), options: ["0", "1", "2"], defaultValue: "1", displayDuringSetup: true, required: false)
        input(name: "humidityOffset", type: "decimal", title: styling_addTitleDiv("Humidity Offset"), description: styling_addDescriptionDiv("Adjust the humidity by this many percent."), displayDuringSetup: true, required: false, range: "*..*")
	}
}

/* These functions are unique to each driver */

ArrayList<String> refresh() {
    logging("refresh() model='${getDeviceDataByName('model')}'", 10)

    unschedule()
    
    startCheckEventInterval()
    resetBatteryReplacedDate(forced=false)
    setLogsOffTask(noLogWarning=true)

    ArrayList<String> cmd = []
    
    logging("refresh cmd: $cmd", 1)
    return cmd
}

void initialize() {
    logging("initialize()", 100)
    refresh()
    configureDevice()
}

void installed() {
    logging("installed()", 100)
    refresh()
    configureDevice()
}

void configureDevice() {
    Integer endpointId = 1
    ArrayList<String> cmd = []
    cmd += zigbee.readAttribute(0x0000, [0x0001, 0x0004, 0x0005, 0x0006])
    cmd += ["zdo bind 0x${device.deviceNetworkId} ${endpointId} 0x01 0x0402 {${device.zigbeeId}} {}", "delay 50"]
    cmd += ["zdo bind 0x${device.deviceNetworkId} ${endpointId} 0x01 0x0405 {${device.zigbeeId}} {}", "delay 50"]
    cmd += ["zdo bind 0x${device.deviceNetworkId} ${endpointId} 0x01 0x0001 {${device.zigbeeId}} {}", "delay 50"]
    cmd += ["he cr 0x${device.deviceNetworkId} ${endpointId} 0x0001 0 0x10 0 0xE10 {}", "delay 50"]
    cmd += zigbee.configureReporting(0x0402, 0x0000, 0x29, 60, 3600, 20, [:], 50)
    cmd += zigbee.configureReporting(0x0405, 0x0000, 0x29, 60, 3600, 200, [:], 50)

    cmd += zigbee.readAttribute(0x0001, [0x0020, 0x0021])
    cmd += zigbeeReadAttribute(0x0402, 0x0000)
    cmd += zigbeeReadAttribute(0x0405, 0x0000)
    
    sendZigbeeCommands(cmd)
}

void updated() {
    logging("updated()", 100)
    refresh()
}

Integer getMINUTES_BETWEEN_EVENTS() {
    return 140
}

ArrayList<String> parse(String description) {
    // BEGIN:getGenericZigbeeParseHeader(loglevel=0)
    //logging("PARSE START---------------------", 0)
    //logging("Parsing: '${description}'", 0)
    ArrayList<String> cmd = []
    Map msgMap = null
    if(description.indexOf('encoding: 4C') >= 0) {
    
      msgMap = zigbee.parseDescriptionAsMap(description.replace('encoding: 4C', 'encoding: F2'))
    
      msgMap = unpackStructInMap(msgMap)
    
    } else if(description.indexOf('attrId: FF01, encoding: 42') >= 0) {
      msgMap = zigbee.parseDescriptionAsMap(description.replace('encoding: 42', 'encoding: F2'))
      msgMap["encoding"] = "41"
      msgMap["value"] = parseXiaomiStruct(msgMap["value"], isFCC0=false, hasLength=true)
    } else {
      if(description.indexOf('encoding: 42') >= 0) {
    
        List values = description.split("value: ")[1].split("(?<=\\G..)")
        String fullValue = values.join()
        Integer zeroIndex = values.indexOf("01")
        if(zeroIndex > -1) {
    
          //logging("zeroIndex: $zeroIndex, fullValue: $fullValue, string: ${values.take(zeroIndex).join()}", 0)
          msgMap = zigbee.parseDescriptionAsMap(description.replace(fullValue, values.take(zeroIndex).join()))
    
          values = values.drop(zeroIndex + 3)
          msgMap["additionalAttrs"] = [
              ["encoding": "41",
              "value": parseXiaomiStruct(values.join(), isFCC0=false, hasLength=true)]
          ]
        } else {
          msgMap = zigbee.parseDescriptionAsMap(description)
        }
      } else {
        msgMap = zigbee.parseDescriptionAsMap(description)
      }
    
      if(msgMap.containsKey("encoding") && msgMap.containsKey("value") && msgMap["encoding"] != "41" && msgMap["encoding"] != "42") {
        msgMap["valueParsed"] = zigbee_generic_decodeZigbeeData(msgMap["value"], msgMap["encoding"])
      }
      if(msgMap == [:] && description.indexOf("zone") == 0) {
    
        msgMap["type"] = "zone"
        java.util.regex.Matcher zoneMatcher = description =~ /.*zone.*status.*0x(?<status>([0-9a-fA-F][0-9a-fA-F])+).*extended.*status.*0x(?<statusExtended>([0-9a-fA-F][0-9a-fA-F])+).*/
        if(zoneMatcher.matches()) {
          msgMap["parsed"] = true
          msgMap["status"] = zoneMatcher.group("status")
          msgMap["statusInt"] = Integer.parseInt(msgMap["status"], 16)
          msgMap["statusExtended"] = zoneMatcher.group("statusExtended")
          msgMap["statusExtendedInt"] = Integer.parseInt(msgMap["statusExtended"], 16)
        } else {
          msgMap["parsed"] = false
        }
      }
    }
    //logging("msgMap: ${msgMap}", 0)
    // END:  getGenericZigbeeParseHeader(loglevel=0)
    
    //logging("Parse START: description:${description} | parseMap:${msgMap}", 0)

    switch(msgMap["cluster"] + '_' + msgMap["attrId"]) {
        case "0000_0001":
            //logging("Cluster 0000 - description:${description} | parseMap:${msgMap}", 0)

            break
        case "0000_0004":
            logging("Manufacturer Name Received (from readAttribute command) - description:${description} | parseMap:${msgMap}", 1)
            
            break
        case "0001_0020":
        case "0001_0021":
            logging("Battery data - description:${description} | parseMap:${msgMap}", 100)
            zigbee_sonoff_parseBatteryData(msgMap)
            break
        case "0402_0000":
            logging("SONOFF TEMPERATURE EVENT - description:${description} | parseMap:${msgMap}", 100)
            zigbee_sensor_parseSendTemperatureEvent(msgMap['valueParsed'])

            break
        case "0405_0000":
            logging("SONOFF HUMIDITY EVENT - description:${description} | parseMap:${msgMap}", 100)
            zigbee_sensor_parseSendHumidityEvent(msgMap['valueParsed'])

            break
        default:
            switch(msgMap["clusterId"]) {
                case "0001":
                    //logging("Broadcast catchall - description:${description} | parseMap:${msgMap}", 0)
                    
                    break
                case "0013":
                    //logging("Device Announcement Cluster - description:${description} | parseMap:${msgMap}", 0)
                    
                    configureDevice()

                    break
                case "0402":
                    logging("Configuration Accepted for cluster 0x0402", 100)
                    break
                case "0405":
                    logging("Configuration Accepted for cluster 0x0405", 100)
                    break
                case "8021":
                    //logging("General catchall - description:${description} | parseMap:${msgMap}", 0)
                    break
                default:
                    log.warn "Unhandled Event PLEASE REPORT TO DEV - description:${description} | msgMap:${msgMap}"
            }
            break
    }

    if(hasCorrectCheckinEvents(maximumMinutesBetweenEvents=140) == false) {
        sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
    }
    sendlastCheckinEvent(minimumMinutesToRepeat=30)
    
    // BEGIN:getGenericZigbeeParseFooter(loglevel=0)
    //logging("PARSE END-----------------------", 0)
    msgMap = null
    return cmd
    // END:  getGenericZigbeeParseFooter(loglevel=0)
}

void reconnectEventDeviceSpecific() {
    logging("reconnectEventDeviceSpecific() T&H", 1)
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
}

/**
 *  -----------------------------------------------------------------------------
 *  Everything below here are LIBRARY includes and should NOT be edited manually!
 *  -----------------------------------------------------------------------------
 *  --- Nothings to edit here, move along! --------------------------------------
 *  -----------------------------------------------------------------------------
 */


// BEGIN:getLoggingFunction()
private boolean logging(message, level) {
    boolean didLogging = false
     
    Integer logLevelLocal = 0
    if (infoLogging == null || infoLogging == true) {
        logLevelLocal = 100
    }
    if (debugLogging == true) {
        logLevelLocal = 1
    }
     
    if (logLevelLocal != 0){
        switch (logLevelLocal) {
        case 1:  
            if (level >= 1 && level < 99) {
                log.debug "$message"
                didLogging = true
            } else if (level == 100) {
                log.info "$message"
                didLogging = true
            }
        break
        case 100:  
            if (level == 100 ) {
                log.info "$message"
                didLogging = true
            }
        break
        }
    }
    return didLogging
}
// END:  getLoggingFunction()

// BEGIN:getHelperFunctions('all-default')
boolean isDriver() {
    try {
        getDeviceDataByName('_unimportant')
         
        return true
    } catch (MissingMethodException e) {
         
        return false
    }
}

void deviceCommand(String cmd) {
    def jsonSlurper = new JsonSlurper()
    cmd = jsonSlurper.parseText(cmd)
     
    r = this."${cmd['cmd']}"(*cmd['args'])
     
    updateDataValue('appReturn', JsonOutput.toJson(r))
}

void setLogsOffTask(boolean noLogWarning=false) {
	if (debugLogging == true) {
        if(noLogWarning==false) {
            if(runReset != "DEBUG") {
                log.warn "Debug logging will be disabled in 30 minutes..."
            } else {
                log.warn "Debug logging will NOT BE AUTOMATICALLY DISABLED!"
            }
        }
        runIn(1800, "logsOff")
    }
}

def generalInitialize() {
    logging("generalInitialize()", 100)
	unschedule("tasmota_updatePresence")
    setLogsOffTask()
    refresh()
}

void logsOff() {
    if(runReset != "DEBUG") {
        log.warn "Debug logging disabled..."
        if(isDriver()) {
            device.clearSetting("logLevel")
            device.removeSetting("logLevel")
            device.updateSetting("logLevel", "0")
            state?.settings?.remove("logLevel")
            device.clearSetting("debugLogging")
            device.removeSetting("debugLogging")
            device.updateSetting("debugLogging", "false")
            state?.settings?.remove("debugLogging")
            
        } else {
            app.removeSetting("logLevel")
            app.updateSetting("logLevel", "0")
            app.removeSetting("debugLogging")
            app.updateSetting("debugLogging", "false")
        }
    } else {
        log.warn "OVERRIDE: Disabling Debug logging will not execute with 'DEBUG' set..."
        if (logLevel != "0" && logLevel != "100") runIn(1800, "logsOff")
    }
}

// BEGIN:getHelperFunctions('zigbee-generic')
private getCLUSTER_BASIC() { 0x0000 }
private getCLUSTER_POWER() { 0x0001 }
private getCLUSTER_WINDOW_COVERING() { 0x0102 }
private getCLUSTER_WINDOW_POSITION() { 0x000d }
private getCLUSTER_ON_OFF() { 0x0006 }
private getBASIC_ATTR_POWER_SOURCE() { 0x0007 }
private getPOWER_ATTR_BATTERY_PERCENTAGE_REMAINING() { 0x0021 }
private getPOSITION_ATTR_VALUE() { 0x0055 }
private getCOMMAND_OPEN() { 0x00 }
private getCOMMAND_CLOSE() { 0x01 }
private getCOMMAND_PAUSE() { 0x02 }
private getENCODING_SIZE() { 0x39 }

ArrayList<String> zigbeeCommand(Integer cluster, Integer command, Map additionalParams, int delay = 200, String... payload) {
    ArrayList<String> cmd = zigbee.command(cluster, command, additionalParams, delay, payload)
    cmd[0] = cmd[0].replace('0xnull', '0x01')
     
    return cmd
}

ArrayList<String> zigbeeCommand(Integer cluster, Integer command, int delay = 200, String... payload) {
    ArrayList<String> cmd = zigbee.command(cluster, command, [:], delay, payload)
    cmd[0] = cmd[0].replace('0xnull', '0x01')
     
    return cmd
}

ArrayList<String> zigbeeCommand(Integer endpoint, Integer cluster, Integer command, int delay = 200, String... payload) {
    zigbeeCommand(endpoint, cluster, command, [:], delay, payload)
}

ArrayList<String> zigbeeCommand(Integer endpoint, Integer cluster, Integer command, Map additionalParams, int delay = 200, String... payload) {
    String mfgCode = ""
    if(additionalParams.containsKey("mfgCode")) {
        mfgCode = " {${HexUtils.integerToHexString(HexUtils.hexStringToInt(additionalParams.get("mfgCode")), 2)}}"
    }
    String finalPayload = payload != null && payload != [] ? payload[0] : ""
    String cmdArgs = "0x${device.deviceNetworkId} 0x${HexUtils.integerToHexString(endpoint, 1)} 0x${HexUtils.integerToHexString(cluster, 2)} " + 
                       "0x${HexUtils.integerToHexString(command, 1)} " + 
                       "{$finalPayload}" + 
                       "$mfgCode"
    ArrayList<String> cmd = ["he cmd $cmdArgs", "delay $delay"]
    return cmd
}

ArrayList<String> zigbeeWriteAttribute(Integer cluster, Integer attributeId, Integer dataType, Integer value, Map additionalParams = [:], int delay = 200) {
    ArrayList<String> cmd = zigbee.writeAttribute(cluster, attributeId, dataType, value, additionalParams, delay)
    cmd[0] = cmd[0].replace('0xnull', '0x01')
     
    return cmd
}

ArrayList<String> zigbeeReadAttribute(Integer cluster, Integer attributeId, Map additionalParams = [:], int delay = 200) {
    ArrayList<String> cmd = zigbee.readAttribute(cluster, attributeId, additionalParams, delay)
    cmd[0] = cmd[0].replace('0xnull', '0x01')
     
    return cmd
}

ArrayList<String> zigbeeReadAttribute(Integer endpoint, Integer cluster, Integer attributeId, int delay = 200) {
    ArrayList<String> cmd = ["he rattr 0x${device.deviceNetworkId} ${endpoint} 0x${HexUtils.integerToHexString(cluster, 2)} 0x${HexUtils.integerToHexString(attributeId, 2)} {}", "delay 200"]
     
    return cmd
}

ArrayList<String> zigbeeWriteLongAttribute(Integer cluster, Integer attributeId, Integer dataType, Long value, Map additionalParams = [:], int delay = 200) {
    logging("zigbeeWriteLongAttribute()", 1)
    String mfgCode = ""
    if(additionalParams.containsKey("mfgCode")) {
        mfgCode = " {${HexUtils.integerToHexString(HexUtils.hexStringToInt(additionalParams.get("mfgCode")), 2)}}"
    }
    String wattrArgs = "0x${device.deviceNetworkId} 0x01 0x${HexUtils.integerToHexString(cluster, 2)} " + 
                       "0x${HexUtils.integerToHexString(attributeId, 2)} " + 
                       "0x${HexUtils.integerToHexString(dataType, 1)} " + 
                       "{${Long.toHexString(value)}}" + 
                       "$mfgCode"
    ArrayList<String> cmd = ["he wattr $wattrArgs", "delay $delay"]
    
    logging("zigbeeWriteLongAttribute cmd=$cmd", 1)
    return cmd
}

void sendZigbeeCommand(String cmd) {
    logging("sendZigbeeCommand(cmd=$cmd)", 1)
    sendZigbeeCommands([cmd])
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    logging("sendZigbeeCommands(cmd=$cmd)", 1)
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
        if(it.startsWith('delay') == true) {
            allActions.add(new hubitat.device.HubAction(it))
        } else {
            allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
        }
    }
    sendHubCommand(allActions)
}

void resetBatteryReplacedDate(boolean forced=true) {
    if(forced == true || state.batteryLastReplaced == null) {
        state.batteryLastReplaced = new Date().format('yyyy-MM-dd HH:mm:ss')
    }
}

void parseAndSendBatteryStatus(BigDecimal vCurrent) {
    BigDecimal vMin = vMinSetting == null ? 2.5 : vMinSetting
    BigDecimal vMax = vMaxSetting == null ? 3.0 : vMaxSetting
    
    BigDecimal bat = 0
    if(vMax - vMin > 0) {
        bat = ((vCurrent - vMin) / (vMax - vMin)) * 100.0
    } else {
        bat = 100
    }
    bat = bat.setScale(0, BigDecimal.ROUND_HALF_UP)
    bat = bat > 100 ? 100 : bat
    
    vCurrent = vCurrent.setScale(3, BigDecimal.ROUND_HALF_UP)

    logging("Battery event: $bat% (V = $vCurrent)", 1)
    sendEvent(name:"battery", value: bat, unit: "%", isStateChange: false)
}

Map unpackStructInMap(Map msgMap, String originalEncoding="4C") {
     
    msgMap['encoding'] = originalEncoding
    List<String> values = msgMap['value'].split("(?<=\\G..)")
    logging("unpackStructInMap() values=$values", 1)
    Integer numElements = Integer.parseInt(values.take(2).reverse().join(), 16)
    values = values.drop(2)
    List r = []
    Integer cType = null
    List ret = null
    while(values != []) {
        cType = Integer.parseInt(values.take(1)[0], 16)
        values = values.drop(1)
        ret = zigbee_generic_convertStructValueToList(values, cType)
        r += ret[0]
        values = ret[1]
    }
    if(r.size() != numElements) throw new Exception("The STRUCT specifies $numElements elements, found ${r.size()}!")
     
    msgMap['value'] = r
    return msgMap
}

Map parseXiaomiStruct(String xiaomiStruct, boolean isFCC0=false, boolean hasLength=false) {
     
    Map tags = [
        '01': 'battery',
        '03': 'deviceTemperature',
        '04': 'unknown1',
        '05': 'RSSI_dB',
        '06': 'LQI',
        '07': 'unknown2',
        '08': 'unknown3',
        '09': 'unknown4',
        '0A': 'unknown5',
        '0B': 'unknown6',
        '0C': 'unknown6',
        '6429': 'temperature',
        '6410': 'openClose',
        '6420': 'curtainPosition',
        '65': 'humidity',
        '66': 'pressure',
        '95': 'consumption',
        '96': 'voltage',
        '9721': 'gestureCounter1',
        '9739': 'consumption',
        '9821': 'gestureCounter2',
        '9839': 'power',
        '99': 'gestureCounter3',
        '9A21': 'gestureCounter4',
        '9A20': 'unknown7',
        '9A25': 'unknown8',
        '9B': 'unknown9',
    ]
    if(isFCC0 == true) {
        tags['05'] = 'numBoots'
        tags['6410'] = 'onOff'
        tags['95'] = 'current'
    }

    List<String> values = xiaomiStruct.split("(?<=\\G..)")
    
    if(hasLength == true) values = values.drop(1)
    Map r = [:]
    r["raw"] = [:]
    String cTag = null
    String cTypeStr = null
    Integer cType = null
    String cKey = null
    List ret = null
    while(values != []) {
        cTag = values.take(1)[0]
        values = values.drop(1)
        cTypeStr = values.take(1)[0]
        cType = Integer.parseInt(cTypeStr, 16)
        values = values.drop(1)
        if(tags.containsKey(cTag+cTypeStr)) {
            cKey = tags[cTag+cTypeStr]
        } else if(tags.containsKey(cTag)) {
            cKey = tags[cTag]
        } else {
            throw new Exception("The Xiaomi Struct used an unrecognized tag: 0x$cTag (type: 0x$cTypeStr)")
        }
        ret = zigbee_generic_convertStructValue(r, values, cType, cKey, cTag)
        r = ret[0]
        values = ret[1]
    }
     
    return r
}

Map parseAttributeStruct(List data, boolean hasLength=false) {
     
    Map tags = [
        '0000': 'ZCLVersion',
        '0001': 'applicationVersion',
        '0002': 'stackVersion',
        '0003': 'HWVersion',
        '0004': 'manufacturerName',
        '0005': 'dateCode',
        '0006': 'modelIdentifier',
        '0007': 'powerSource',
        '0010': 'locationDescription',
        '0011': 'physicalEnvironment',
        '0012': 'deviceEnabled',
        '0013': 'alarmMask',
        '0014': 'disableLocalConfig',
        '4000': 'SWBuildID',
    ]
    
    List<String> values = data
    
    if(hasLength == true) values = values.drop(1)
    Map r = [:]
    r["raw"] = [:]
    String cTag = null
    String cTypeStr = null
    Integer cType = null
    String cKey = null
    List ret = null
    while(values != []) {
        cTag = values.take(2).reverse().join()
        values = values.drop(2)
        values = values.drop(1)
        cTypeStr = values.take(1)[0]
        cType = Integer.parseInt(cTypeStr, 16)
        values = values.drop(1)
        if(tags.containsKey(cTag+cTypeStr)) {
            cKey = tags[cTag+cTypeStr]
        } else if(tags.containsKey(cTag)) {
            cKey = tags[cTag]
        } else {
            throw new Exception("The Xiaomi Struct used an unrecognized tag: 0x$cTag (type: 0x$cTypeStr)")
        }
        ret = zigbee_generic_convertStructValue(r, values, cType, cKey, cTag)
        r = ret[0]
        values = ret[1]
    }
     
    return r
}

def zigbee_generic_decodeZigbeeData(String value, String cTypeStr, boolean reverseBytes=true) {
    List values = value.split("(?<=\\G..)")
    values = reverseBytes == true ? values.reverse() : values
    Integer cType = Integer.parseInt(cTypeStr, 16)
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = zigbee_generic_convertStructValue(rMap, values, cType, "NA", "NA")
    return ret[0]["NA"]
}

List zigbee_generic_convertStructValueToList(List values, Integer cType) {
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = zigbee_generic_convertStructValue(rMap, values, cType, "NA", "NA")
    return [ret[0]["NA"], ret[1]]
}

List zigbee_generic_convertStructValue(Map r, List values, Integer cType, String cKey, String cTag) {
    String cTypeStr = cType != null ? integerToHexString(cType, 1) : null
    switch(cType) {
        case 0x10:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16) != 0
            values = values.drop(1)
            break
        case 0x18:
        case 0x20:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x19:
        case 0x21:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x1A:
        case 0x22:
            r["raw"][cKey] = values.take(3).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(3)
            break
        case 0x1B:
        case 0x23:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x1C:
        case 0x24:
            r["raw"][cKey] = values.take(5).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(5)
            break
        case 0x1D:
        case 0x25:
            r["raw"][cKey] = values.take(6).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(6)
            break
        case 0x1E:
        case 0x26:
            r["raw"][cKey] = values.take(7).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(7)
            break
        case 0x1F:
        case 0x27:
            r["raw"][cKey] = values.take(8).reverse().join()
            r[cKey] = new BigInteger(r["raw"][cKey], 16)
            values = values.drop(8)
            break
        case 0x28:
            r["raw"][cKey] = values.take(1).reverse().join()
            r[cKey] = convertToSignedInt8(Integer.parseInt(r["raw"][cKey], 16))
            values = values.drop(1)
            break
        case 0x29:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = (Integer) (short) Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x2B:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = (Integer) Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x30:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x31:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x39:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = parseSingleHexToFloat(r["raw"][cKey])
            values = values.drop(4)
            break
        case 0x42:
            Integer strLength = Integer.parseInt(values.take(1)[0], 16)
            values = values.drop(1)
            r["raw"][cKey] = values.take(strLength)
            r[cKey] = r["raw"][cKey].collect { 
                (char)(int) Integer.parseInt(it, 16)
            }.join()
            values = values.drop(strLength)
            break
        default:
            throw new Exception("The Struct used an unrecognized type: $cTypeStr ($cType) for tag 0x$cTag with key $cKey (values: $values, map: $r)")
    }
    return [r, values]
}

ArrayList<String> zigbeeWriteHexStringAttribute(Integer cluster, Integer attributeId, Integer dataType, String value, Map additionalParams = [:], int delay = 200) {
    logging("zigbeeWriteBigIntegerAttribute()", 1)
    String mfgCode = ""
    if(additionalParams.containsKey("mfgCode")) {
        mfgCode = " {${integerToHexString(HexUtils.hexStringToInt(additionalParams.get("mfgCode")), 2, reverse=true)}}"
    }
    String wattrArgs = "0x${device.deviceNetworkId} 0x01 0x${HexUtils.integerToHexString(cluster, 2)} " + 
                       "0x${HexUtils.integerToHexString(attributeId, 2)} " + 
                       "0x${HexUtils.integerToHexString(dataType, 1)} " + 
                       "{${value.split("(?<=\\G..)").reverse().join()}}" + 
                       "$mfgCode"
    ArrayList<String> cmd = ["he wattr $wattrArgs", "delay $delay"]
    
    logging("zigbeeWriteBigIntegerAttribute cmd=$cmd", 1)
    return cmd
}

ArrayList<String> zigbeeReadAttributeList(Integer cluster, List<Integer> attributeIds, Map additionalParams = [:], int delay = 2000) {
    logging("zigbeeReadAttributeList()", 1)
    String mfgCode = "0000"
    if(additionalParams.containsKey("mfgCode")) {
        mfgCode = "${integerToHexString(HexUtils.hexStringToInt(additionalParams.get("mfgCode")), 2, reverse=true)}"
        log.error "Manufacturer code support is NOT implemented!"
    }
    List<String> attributeIdsString = []
    attributeIds.each { attributeIdsString.add(integerToHexString(it, 2, reverse=true)) }
    logging("attributeIds=$attributeIds, attributeIdsString=$attributeIdsString", 100)
    String rattrArgs = "0x${device.deviceNetworkId} 1 0x01 0x${integerToHexString(cluster, 2)} " + 
                       "{000000${attributeIdsString.join()}}"
    ArrayList<String> cmd = ["he raw $rattrArgs", "delay $delay"]
    logging("zigbeeWriteLongAttribute cmd=$cmd", 1)
    return cmd
}

Float parseSingleHexToFloat(String singleHex) {
    return Float.intBitsToFloat(Long.valueOf(singleHex, 16).intValue())
}

Integer convertToSignedInt8(Integer signedByte) {
    Integer sign = signedByte & (1 << 7);
    return (signedByte & 0x7f) * (sign != 0 ? -1 : 1);
}

Integer parseIntReverseHex(String hexString) {
    return Integer.parseInt(hexString.split("(?<=\\G..)").reverse().join(), 16)
}

Long parseLongReverseHex(String hexString) {
    return Long.parseLong(hexString.split("(?<=\\G..)").reverse().join(), 16)
}

String integerToHexString(BigDecimal value, Integer minBytes, boolean reverse=false) {
    return integerToHexString(value.intValue(), minBytes, reverse=reverse)
}

String integerToHexString(Integer value, Integer minBytes, boolean reverse=false) {
    if(reverse == true) {
        return HexUtils.integerToHexString(value, minBytes).split("(?<=\\G..)").reverse().join()
    } else {
        return HexUtils.integerToHexString(value, minBytes)
    }
    
}

String bigIntegerToHexString(BigInteger value, Integer minBytes, boolean reverse=false) {
    if(reverse == true) {
        return value.toString(16).reverse().join()
    } else {
        return String.format("%0${minBytes*2}x", value)
    }
}

BigInteger hexStringToBigInteger(String hexString, boolean reverse=false) {
    if(reverse == true) {
        return new BigInteger(hexString.split("(?<=\\G..)").reverse().join(), 16)
    } else {
        return new BigInteger(hexString, 16)
    }
}

Integer miredToKelvin(Integer mired) {
    Integer t = mired
    if(t < 153) t = 153
    if(t > 500) t = 500
    t = Math.round(1000000/t)
    if(t > 6536) t = 6536
    if(t < 2000) t = 2000
    return t
}

Integer kelvinToMired(Integer kelvin) {
    Integer t = kelvin
    if(t > 6536) t = 6536
    if(t < 2000) t = 2000
    t = Math.round(1000000/t)
    if(t < 153) t = 153
    if(t > 500) t = 500
    return t
}

void reconnectEvent() {
    try {
        reconnectEventDeviceSpecific()
    } catch(Exception e) {
        logging("reconnectEvent()", 1)
        sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
    }
    //checkPresence(displayWarnings=false)
    Integer mbe = MINUTES_BETWEEN_EVENTS == null ? 90 : MINUTES_BETWEEN_EVENTS
    if(hasCorrectCheckinEvents(maximumMinutesBetweenEvents=mbe, displayWarnings=false) == true) {
        log.warn("Event interval normal, reconnect mode DEACTIVATED!")
        unschedule('reconnectEvent')
    }
}

void checkEventInterval(boolean displayWarnings=true) {
    //prepareCounters()
    Integer mbe = MINUTES_BETWEEN_EVENTS == null ? 90 : MINUTES_BETWEEN_EVENTS
    if(hasCorrectCheckinEvents(maximumMinutesBetweenEvents=mbe) == false) {
        if(displayWarnings == true) log.warn("Event interval INCORRECT, reconnect mode ACTIVE! If this is shown every hour for the same device and doesn't go away after three times, the device has probably fallen off and require a quick press of the reset button or possibly even re-pairing. It MAY also return within 24 hours, so patience MIGHT pay off.")
        Random rnd = new Random()
        schedule("${rnd.nextInt(15)}/15 * * * * ? *", 'reconnectEvent')
    }
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
}

void startCheckEventInterval() {
    logging("startCheckEventInterval()", 100)
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(59)}/59 * * * ? *", 'checkEventInterval')
    checkEventInterval(displayWarnings=true)
}
// END:  getHelperFunctions('zigbee-generic')

// BEGIN:getHelperFunctions('zigbee-sonoff')
void zigbee_sonoff_parseBatteryData(Map msgMap) {
    BigDecimal bat = null
    if(msgMap["attrId"] == "0021") {
        bat = msgMap['valueParsed'] / 2.0
    } else if(msgMap.containsKey("additionalAttrs") == true) {
        msgMap["additionalAttrs"].each() {
            if(it.containsKey("attrId") == true && it['attrId'] == "0021") {
                bat = Integer.parseInt(it['value'], 16) / 2.0
            }
        }
    }
    if(bat != null) {
        bat = bat.setScale(1, BigDecimal.ROUND_HALF_UP)
        sendEvent(name:"battery", value: bat , unit: "%", isStateChange: false)
    }
}
// END:  getHelperFunctions('zigbee-sonoff')

// BEGIN:getHelperFunctions('zigbee-sensor')
void zigbee_sensor_parseSendTemperatureEvent(Integer rawValue, BigDecimal variance = 0.2, Integer minAllowed=-50, Integer maxAllowed=100) {
    
    List adjustedTemp = sensor_data_getAdjustedTempAlternative(rawValue / 100.0 )
    String tempUnit = adjustedTemp[0]
    BigDecimal t = adjustedTemp[1]
    BigDecimal tRaw = adjustedTemp[2]
    
    if(tRaw >= -50 && tRaw < 100) {
        BigDecimal oldT = device.currentValue('temperature') == null ? null : device.currentValue('temperature')
        t = t.setScale(1, BigDecimal.ROUND_HALF_UP)
        if(oldT != null) oldT = oldT.setScale(1, BigDecimal.ROUND_HALF_UP)
        BigDecimal tChange = null
        if(oldT == null) {
            logging("Temperature: $t $tempUnit", 1)
        } else {
            tChange = Math.abs(t - oldT)
            tChange = tChange.setScale(1, BigDecimal.ROUND_HALF_UP)
            logging("Temperature: $t $tempUnit (old temp: $oldT, change: $tChange)", 1)
        }
        
        if(oldT == null || tChange > variance) {
            logging("Sending temperature event (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 100)
            sendEvent(name:"temperature", value: t, unit: "$tempUnit", isStateChange: true)
        } else {
            logging("SKIPPING temperature event since the change wasn't large enough (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 1)
        }
    } else {
        log.warn "Incorrect temperature received from the sensor ($tRaw), it is probably time to change batteries!"
    }
}

// void zigbee_sensor_parseSendPressureEvent(Map msgMap) {
//     Integer rawValue = msgMap['valueParsed']
//     BigDecimal variance = 0.0
//     if(msgMap["attrId"] == "0020") {
//         rawValue = rawValue / 1000.0
//     }
//     BigDecimal p = sensor_data_getAdjustedPressure(sensor_data_convertPressure(rawValue), decimals=2)
//     BigDecimal oldP = device.currentValue('pressure') == null ? null : device.currentValue('pressure')
//     p = p.setScale(2, BigDecimal.ROUND_HALF_UP)
//     if(oldP != null) oldP = oldP.setScale(2, BigDecimal.ROUND_HALF_UP)
//     BigDecimal pChange = null
//     if(oldP == null) {
//         logging("Pressure: $p", 1)
//     } else {
//         pChange = Math.abs(p - oldP)
//         pChange = pChange.setScale(2, BigDecimal.ROUND_HALF_UP)
//         logging("Pressure: $p (old pressure: $oldP, change: $pChange)", 1)
//     }
//     String pUnit = pressureUnitConversion == null ? "kPa" : pressureUnitConversion
//     if(oldP == null || pChange > variance) {
//         logging("Sending pressure event (Pressure: $p, old pressure: $oldP, change: $pChange)", 100)
//         sendEvent(name:"pressure", value: p, unit: "$pUnit", isStateChange: true)
//     } else {
//         logging("SKIPPING pressure event since the change wasn't large enough (Pressure: $p, old pressure: $oldP, change: $pChange)", 1)
//     }
// }

void zigbee_sensor_parseSendHumidityEvent(Integer rawValue, BigDecimal variance = 0.02) {
    BigDecimal h = sensor_data_getAdjustedHumidity(rawValue / 100.0)
    BigDecimal oldH = device.currentValue('humidity') == null ? null : device.currentValue('humidity')
    h = h.setScale(1, BigDecimal.ROUND_HALF_UP)
    if(oldH != null) oldH = oldH.setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal hChange = null
    if(h <= 100) {
        if(oldH == null) {
            logging("Humidity: $h %", 1)
        } else {
            hChange = Math.abs(h - oldH)
            hChange = hChange.setScale(2, BigDecimal.ROUND_HALF_UP)
            logging("Humidity: $h% (old humidity: $oldH%, change: $hChange%)", 1)
        }
        
        if(oldH == null || hChange > variance) {
            logging("Sending humidity event (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 100)
            sendEvent(name:"humidity", value: h, unit: "%", isStateChange: true)
        } else {
            logging("SKIPPING humidity event since the change wasn't large enough (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 1)
        }
    }
}
// END:  getHelperFunctions('zigbee-sensor')

// BEGIN:getHelperFunctions('styling')
String styling_addTitleDiv(title) {
    return '<div class="preference-title">' + title + '</div>'
}

String styling_addDescriptionDiv(description) {
    return '<div class="preference-description">' + description + '</div>'
}

String styling_makeTextBold(s) {
    if(isDriver()) {
        return "<b>$s</b>"
    } else {
        return "$s"
    }
}

String styling_makeTextItalic(s) {
    if(isDriver()) {
        return "<i>$s</i>"
    } else {
        return "$s"
    }
}

String styling_getDefaultCSS(boolean includeTags=true) {
    String defaultCSS = '''
    /* This is part of the CSS for replacing a Command Title */
    div.mdl-card__title div.mdl-grid div.mdl-grid .mdl-cell p::after {
        visibility: visible;
        position: absolute;
        left: 50%;
        transform: translate(-50%, 0%);
        width: calc(100% - 20px);
        padding-left: 5px;
        padding-right: 5px;
        margin-top: 0px;
    }
    /* This is general CSS Styling for the Driver page */
    h3, h4, .property-label {
        font-weight: bold;
    }
    .preference-title {
        font-weight: bold;
    }
    .preference-description {
        font-style: italic;
    }
    '''
    if(includeTags == true) {
        return "<style>$defaultCSS </style>"
    } else {
        return defaultCSS
    }
}
// END:  getHelperFunctions('styling')

// BEGIN:getHelperFunctions('driver-default')
void refresh(String cmd) {
    deviceCommand(cmd)
}
def installedDefault() {
	logging("installedDefault()", 100)
    
    try {
        tasmota_installedPreConfigure()
    } catch (MissingMethodException e) {
    }
    try {
        installedAdditional()
    } catch (MissingMethodException e) {
    }
}

def configureDefault() {
    logging("configureDefault()", 100)
    try {
        return configureAdditional()
    } catch (MissingMethodException e) {
    }
    try {
        //getDriverVersion()
    } catch (MissingMethodException e) {
    }
}

void configureDelayed() {
    runIn(10, "configure")
    runIn(30, "refresh")
}

boolean isValidDate(String dateFormat, String dateString) {
    try {
        Date.parse(dateFormat, dateString)
    } catch (e) {
        return false
    }
    return true
}

boolean sendlastCheckinEvent(Integer minimumMinutesToRepeat=55) {
    boolean r = false
    if (lastCheckinEnable == true || lastCheckinEnable == null) {
		if(state.lastCheckin == null || now() >= state.lastCheckin + (minimumMinutesToRepeat * 60 * 1000)) {
            r = true
            state.lastCheckin = now()
            logging("Updated lastCheckinEpoch", 1)
        } else {
             
        }
	}
    return r
}

Long secondsSinceLastCheckinEvent() {
    Long r = null
    if (lastCheckinEnable == true || lastCheckinEnable == null) {
		if(state.lastCheckin == null) {
		    logging("No VALID lastCheckin event available! This should be resolved by itself within 1 or 2 hours and is perfectly NORMAL as long as the same device don't get this multiple times per day...", 100)
            r = r == null ? -1 : r
        } else {
            r = (now() - state.lastCheckin) / 1000
        }
	}
    return r
}

boolean hasCorrectCheckinEvents(Integer maximumMinutesBetweenEvents=90, boolean displayWarnings=true) {
    Long secondsSinceLastCheckin = secondsSinceLastCheckinEvent()
    if(secondsSinceLastCheckin != null && secondsSinceLastCheckin > maximumMinutesBetweenEvents * 60) {
        if(displayWarnings == true) log.warn("One or several EXPECTED checkin events have been missed! Something MIGHT be wrong with the mesh for this device. Minutes since last checkin: ${Math.round(secondsSinceLastCheckin / 60)} (maximum expected $maximumMinutesBetweenEvents)")
        return false
    }
    return true
}

// BEGIN:getHelperFunctions('sensor-data')
private BigDecimal sensor_data_getAdjustedTemp(BigDecimal value) {
    Integer res = 1
    if(tempRes != null && tempRes != '') {
        res = Integer.parseInt(tempRes)
    }
    if (tempUnitConversion == "2") {
        value = celsiusToFahrenheit(value)
    } else if (tempUnitConversion == "3") {
        value = fahrenheitToCelsius(value)
    }
	if (tempOffset != null) {
	   return (value + new BigDecimal(tempOffset)).setScale(res, BigDecimal.ROUND_HALF_UP)
	} else {
       return value.setScale(res, BigDecimal.ROUND_HALF_UP)
    }
}

private List sensor_data_getAdjustedTempAlternative(BigDecimal value) {
    Integer res = 1
    BigDecimal rawValue = value
    if(tempRes != null && tempRes != '') {
        res = Integer.parseInt(tempRes)
    }
    String degree = String.valueOf((char)(176))
    String tempUnit = "${degree}C"
    if (tempUnitDisplayed == "2") {
        value = celsiusToFahrenheit(value)
        tempUnit = "${degree}F"
    } else if (tempUnitDisplayed == "3") {
        value = value + 273.15
        tempUnit = "${degree}K"
    }
	if (tempOffset != null) {
	   return [tempUnit, (value + new BigDecimal(tempOffset)).setScale(res, BigDecimal.ROUND_HALF_UP), rawValue]
	} else {
       return [tempUnit, value.setScale(res, BigDecimal.ROUND_HALF_UP), rawValue]
    }
}

private BigDecimal sensor_data_getAdjustedHumidity(BigDecimal value) {
    if (humidityOffset) {
	   return (value + new BigDecimal(humidityOffset)).setScale(1, BigDecimal.ROUND_HALF_UP)
	} else {
       return value.setScale(1, BigDecimal.ROUND_HALF_UP)
    }
}
