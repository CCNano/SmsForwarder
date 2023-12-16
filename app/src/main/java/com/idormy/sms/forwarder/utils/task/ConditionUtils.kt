package com.idormy.sms.forwarder.utils.task

import android.os.BatteryManager
import android.util.Log
import com.google.gson.Gson
import com.idormy.sms.forwarder.entity.task.BatterySetting
import com.idormy.sms.forwarder.entity.task.ChargeSetting
import com.idormy.sms.forwarder.entity.task.CronSetting
import com.idormy.sms.forwarder.entity.task.LocationSetting
import com.idormy.sms.forwarder.entity.task.LockScreenSetting
import com.idormy.sms.forwarder.entity.task.NetworkSetting
import com.idormy.sms.forwarder.entity.task.SimSetting
import com.idormy.sms.forwarder.entity.task.TaskSetting
import com.idormy.sms.forwarder.utils.DELAY_TIME_AFTER_SIM_READY
import com.idormy.sms.forwarder.utils.TASK_CONDITION_BATTERY
import com.idormy.sms.forwarder.utils.TASK_CONDITION_CHARGE
import com.idormy.sms.forwarder.utils.TASK_CONDITION_CRON
import com.idormy.sms.forwarder.utils.TASK_CONDITION_LEAVE_ADDRESS
import com.idormy.sms.forwarder.utils.TASK_CONDITION_LOCK_SCREEN
import com.idormy.sms.forwarder.utils.TASK_CONDITION_NETWORK
import com.idormy.sms.forwarder.utils.TASK_CONDITION_SIM
import com.idormy.sms.forwarder.utils.TASK_CONDITION_TO_ADDRESS
import gatewayapps.crondroid.CronExpression
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 自动任务条件工具类
 */
class ConditionUtils private constructor() {

    companion object {

        private val TAG: String = ConditionUtils::class.java.simpleName

        //遍历条件列表，判断是否满足条件，默认不校验第一个条件（第一个条件是触发条件）
        fun checkCondition(taskId: Long, conditionList: MutableList<TaskSetting>, startIndex: Int = 1): Boolean {
            if (startIndex >= conditionList.size) {
                Log.d(TAG, "TASK-$taskId：no condition need to check")
                return true
            }

            //注意：触发条件 = SIM卡已准备就绪时，延迟5秒（给够搜索信号时间）才执行任务
            val firstCondition = conditionList.firstOrNull()
            val needDelay = firstCondition?.type == TASK_CONDITION_SIM && TaskUtils.simState == 5

            for (i in startIndex until conditionList.size) {
                val condition = conditionList[i]
                when (condition.type) {
                    TASK_CONDITION_CRON -> {
                        val cronSetting = Gson().fromJson(condition.setting, CronSetting::class.java)
                        if (cronSetting == null) {
                            Log.d(TAG, "TASK-$taskId：cronSetting is null")
                            continue
                        }

                        val currentDate = if (needDelay) Date((Date().time / 1000) * 1000 - DELAY_TIME_AFTER_SIM_READY) else Date()
                        currentDate.time = currentDate.time / 1000 * 1000
                        val previousSecond = Date(currentDate.time - 1000)
                        val cronExpression = CronExpression(cronSetting.expression)
                        val nextValidTime = cronExpression.getNextValidTimeAfter(previousSecond)
                        nextValidTime.time = nextValidTime.time / 1000 * 1000
                        if (currentDate.time != nextValidTime.time) {
                            Log.d(TAG, "TASK-$taskId：cron condition is not satisfied")
                            return false
                        }
                    }

                    TASK_CONDITION_TO_ADDRESS, TASK_CONDITION_LEAVE_ADDRESS -> {
                        val locationSetting = Gson().fromJson(condition.setting, LocationSetting::class.java)
                        if (locationSetting == null) {
                            Log.d(TAG, "TASK-$taskId：locationSetting is null")
                            continue
                        }
                        val locationOld = TaskUtils.locationInfoOld
                        val locationNew = TaskUtils.locationInfoNew
                        if (locationSetting.calcType == "distance") {
                            val distanceOld = calculateDistance(locationOld.latitude, locationOld.longitude, locationSetting.latitude, locationSetting.longitude)
                            val distanceNew = calculateDistance(locationNew.latitude, locationNew.longitude, locationSetting.latitude, locationSetting.longitude)
                            if (locationSetting.type == "to" && distanceOld > locationSetting.distance && distanceNew <= locationSetting.distance) {
                                continue
                            } else if (locationSetting.type == "leave" && distanceOld <= locationSetting.distance && distanceNew > locationSetting.distance) {
                                continue
                            }
                        } else if (locationSetting.calcType == "address") {
                            if (locationSetting.type == "to" && !locationOld.address.contains(locationSetting.address) && locationNew.address.contains(locationSetting.address)) {
                                continue
                            } else if (locationSetting.type == "leave" && locationOld.address.contains(locationSetting.address) && !locationNew.address.contains(locationSetting.address)) {
                                continue
                            }
                        }
                        return false
                    }

                    TASK_CONDITION_NETWORK -> {
                        val networkSetting = Gson().fromJson(condition.setting, NetworkSetting::class.java)
                        if (networkSetting == null) {
                            Log.d(TAG, "TASK-$taskId：networkSetting is null")
                            continue
                        }

                        if (TaskUtils.networkState != networkSetting.networkState) {
                            Log.d(TAG, "TASK-$taskId：networkState is not match, networkSetting = $networkSetting")
                            return false
                        }

                        //移动网络
                        if (networkSetting.networkState == 1 && networkSetting.dataSimSlot != 0 && TaskUtils.dataSimSlot != networkSetting.dataSimSlot) {
                            Log.d(TAG, "TASK-$taskId：dataSimSlot is not match, networkSetting = $networkSetting")
                            return false
                        }

                        //WiFi
                        else if (networkSetting.networkState == 2 && networkSetting.wifiSsid.isNotEmpty() && TaskUtils.wifiSsid != networkSetting.wifiSsid) {
                            Log.d(TAG, "TASK-$taskId：wifiSsid is not match, networkSetting = $networkSetting")
                            return false
                        }
                    }

                    TASK_CONDITION_SIM -> {
                        val simSetting = Gson().fromJson(condition.setting, SimSetting::class.java)
                        if (simSetting == null) {
                            Log.d(TAG, "TASK-$taskId：simSetting is null")
                            continue
                        }
                        if (TaskUtils.simState != simSetting.simState) {
                            Log.d(TAG, "TASK-$taskId：simState is not match, simSetting = $simSetting")
                            return false
                        }
                    }

                    TASK_CONDITION_BATTERY -> {
                        val batteryLevel = TaskUtils.batteryLevel
                        val batteryStatus = TaskUtils.batteryStatus
                        val batterySetting = Gson().fromJson(condition.setting, BatterySetting::class.java)
                        if (batterySetting == null) {
                            Log.d(TAG, "TASK-$taskId：batterySetting is null")
                            continue
                        }
                        when (batteryStatus) {
                            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> { //充电中
                                if (batterySetting.status != BatteryManager.BATTERY_STATUS_CHARGING) return false
                                if (batterySetting.keepReminding && batteryLevel >= batterySetting.levelMax) {
                                    continue
                                } else if (!batterySetting.keepReminding && batteryLevel == batterySetting.levelMax) {
                                    continue
                                }
                            }

                            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> { //放电中
                                if (batterySetting.status != BatteryManager.BATTERY_STATUS_DISCHARGING) return false
                                if (batterySetting.keepReminding && batteryLevel <= batterySetting.levelMin) {
                                    continue
                                } else if (!batterySetting.keepReminding && batteryLevel == batterySetting.levelMin) {
                                    continue
                                }
                            }
                        }
                    }

                    TASK_CONDITION_CHARGE -> {
                        val chargeSetting = Gson().fromJson(condition.setting, ChargeSetting::class.java)
                        if (chargeSetting == null) {
                            Log.d(TAG, "TASK-$taskId：chargeSetting is null")
                            continue
                        }
                        val batteryStatus = TaskUtils.batteryStatus
                        val batteryPlugged = TaskUtils.batteryPlugged
                        if (batteryStatus != chargeSetting.status || batteryPlugged != chargeSetting.plugged) {
                            return false
                        }
                    }

                    TASK_CONDITION_LOCK_SCREEN -> {
                        val lockScreenSetting = Gson().fromJson(condition.setting, LockScreenSetting::class.java)
                        if (lockScreenSetting == null) {
                            Log.d(TAG, "TASK-$taskId：lockScreenSetting is null")
                            continue
                        }
                        if (TaskUtils.lockScreenAction != lockScreenSetting.action) {
                            return false
                        }
                    }
                }
            }

            return true
        }

        //计算两个经纬度之间的距离
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6378137.0 // 地球平均半径，单位：米
            val latDistance = Math.toRadians(lat2 - lat1)
            val lonDistance = Math.toRadians(lon2 - lon1)
            val a = sin(latDistance / 2) * sin(latDistance / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lonDistance / 2) * sin(lonDistance / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }

    }
}