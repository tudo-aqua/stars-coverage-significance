/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance

import kotlin.math.abs
import tools.aqua.stars.core.evaluation.Predicate
import tools.aqua.stars.data.sumo.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.firstorder.exists

typealias SumoPredicate =
    Predicate<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>

/** Lane index constant for the right lane. */
const val LANE_INDEX_RIGHT = 0
/** Lane index constant for the middle lane. */
const val LANE_INDEX_MIDDLE = 1
/** Lane index constant for the left lane. */
const val LANE_INDEX_LEFT = 2
/** Maximum distance in meters to consider a vehicle as being "besides" the ego vehicle. */
const val VEHICLE_BESIDES_MAX_DISTANCE_METERS = 10.0f
/** Minimum distance in meters to consider a vehicle as being "in front" of the ego vehicle. */
const val VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM = VEHICLE_BESIDES_MAX_DISTANCE_METERS
/** Maximum distance in meters to consider a vehicle as being "in front" of the ego vehicle. */
const val VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO = 100.0f
/** Minimum distance in meters to consider a vehicle as being "in behind" of the ego vehicle. */
const val VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM = VEHICLE_BESIDES_MAX_DISTANCE_METERS
/** Maximum distance in meters to consider a vehicle as being "in behind" of the ego vehicle. */
const val VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO = VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO
/** Speed threshold in km/h to consider a vehicle as slower/same/faster. */
const val SPEED_THRESHOLD_KMH = 15.0f

/** Predicate to check if the ego vehicle is on the left lane. */
val isOnLeftLane: SumoPredicate =
    Predicate("isOnLeftLane") { tick -> tick.ego.currentLane.laneIndex == LANE_INDEX_LEFT }

/** Predicate to check if the ego vehicle is on the middle lane. */
val isOnMiddleLane: SumoPredicate =
    Predicate("isOnMiddleLane") { tick -> tick.ego.currentLane.laneIndex == LANE_INDEX_MIDDLE }

/** Predicate to check if the ego vehicle is on the right lane. */
val isOnRightLane: SumoPredicate =
    Predicate("isOnRightLane") { tick -> tick.ego.currentLane.laneIndex == LANE_INDEX_RIGHT }

/** Helper function to determine if another vehicle is slower than the ego vehicle. */
fun isDrivingFaster(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.speedKmPerHour > ego.speedKmPerHour + SPEED_THRESHOLD_KMH

/**
 * Helper function to determine if another vehicle is driving at about the same speed as the ego
 * vehicle.
 */
fun isDrivingAtSameSpeed(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    abs(otherVehicle.speedKmPerHour - ego.speedKmPerHour) <= SPEED_THRESHOLD_KMH

/** Helper function to determine if another vehicle is slower than the ego vehicle. */
fun isDrivingSlower(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.speedKmPerHour < ego.speedKmPerHour - SPEED_THRESHOLD_KMH

/** Helper function to determine if two vehicles are on the same lane. */
fun isOnSameLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.currentLane == ego.currentLane

/** Helper function to determine if another vehicle is on the left lane of the ego vehicle. */
fun isOnLeftLaneOf(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.currentLane.laneIndex == ego.currentLane.laneIndex + 1

/** Helper function to determine if another vehicle is on the right lane of the ego vehicle. */
fun isOnRightLaneOf(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.currentLane.laneIndex == ego.currentLane.laneIndex - 1

/** Helper function to determine if there is a vehicle in front of the ego vehicle. */
fun isInFrontOf(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.positionOnLaneMeters > ego.positionOnLaneMeters &&
        (otherVehicle.positionOnLaneMeters in
            (ego.positionOnLaneMeters + VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM)..(ego
                    .positionOnLaneMeters + VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO))

/** Helper function to determine if there is a vehicle behind the ego vehicle. */
fun isBehindOf(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.positionOnLaneMeters < ego.positionOnLaneMeters &&
        (otherVehicle.positionOnLaneMeters in
            ((ego.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO)..ego
                    .positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM))

/** Helper function to determine if there is a vehicle besides the ego vehicle. */
fun isBesidesOf(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    otherVehicle.positionOnLaneMeters in
        (ego.positionOnLaneMeters - VEHICLE_BESIDES_MAX_DISTANCE_METERS)..(ego
                .positionOnLaneMeters + VEHICLE_BESIDES_MAX_DISTANCE_METERS)

/**
 * Helper function to determine if there is a vehicle in front of the ego vehicle on the same lane.
 */
fun isInFrontOfSameLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnSameLane(otherVehicle, ego) && isInFrontOf(otherVehicle, ego)

/** Helper function to determine if there is a vehicle behind the ego vehicle on the same lane. */
fun isBehindOfSameLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnSameLane(otherVehicle, ego) && isBehindOf(otherVehicle, ego)

/** Helper function to determine if there is a vehicle besides the ego vehicle on the left lane. */
fun isBesidesOnLeftLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnLeftLaneOf(otherVehicle, ego) && isBesidesOf(otherVehicle, ego)

/** Helper function to determine if there is a vehicle besides the ego vehicle on the right lane. */
fun isBesidesOnRightLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnRightLaneOf(otherVehicle, ego) && isBesidesOf(otherVehicle, ego)

/**
 * Helper function to determine if there is a vehicle in front of the ego vehicle on the left lane.
 */
fun isInFrontOnLeftLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnLeftLaneOf(otherVehicle, ego) && isInFrontOf(otherVehicle, ego)

/**
 * Helper function to determine if there is a vehicle in front of the ego vehicle on the right lane.
 */
fun isInFrontOnRightLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnRightLaneOf(otherVehicle, ego) && isInFrontOf(otherVehicle, ego)

/** Helper function to determine if there is a vehicle behind the ego vehicle on the left lane. */
fun isBehindOnLeftLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnLeftLaneOf(otherVehicle, ego) && isBehindOf(otherVehicle, ego)

/** Helper function to determine if there is a vehicle behind the ego vehicle on the right lane. */
fun isBehindOnRightLane(otherVehicle: Vehicle, ego: Vehicle): Boolean =
    isOnRightLaneOf(otherVehicle, ego) && isBehindOf(otherVehicle, ego)

// region Vehicle in Front Same Lane Predicates

/**
 * Predicate that checks if there is a vehicle in front of the ego vehicle within a certain
 * distance.
 */
val hasVehicleInFrontOnSameLane: SumoPredicate =
    Predicate("hasVehicleInFrontOnSameLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isInFrontOfSameLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is slower than the ego vehicle.
 */
val vehicleOnSameLaneInFrontIsSlower: SumoPredicate =
    Predicate("vehicleOnSameLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOfSameLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is moving at about the same
 * speed as the ego vehicle.
 */
val vehicleOnSameLaneInFrontSameSpeed: SumoPredicate =
    Predicate("vehicleOnSameLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOfSameLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is faster than the ego vehicle.
 */
val vehicleOnSameLaneInFrontIsFaster: SumoPredicate =
    Predicate("vehicleOnSameLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOfSameLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle Behind Same Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle within a certain distance. */
val hasVehicleBehindOnSameLane: SumoPredicate =
    Predicate("hasVehicleBehindOnSameLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isBehindOfSameLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is slower than the
 * ego vehicle.
 */
val vehicleOnSameLaneBehindIsSlower: SumoPredicate =
    Predicate("vehicleOnSameLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOfSameLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is moving at about
 * the same speed as the ego vehicle.
 */
val vehicleOnSameLaneBehindSameSpeed: SumoPredicate =
    Predicate("vehicleOnSameLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOfSameLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is faster than the
 * ego vehicle.
 */
val vehicleOnSameLaneBehindIsFaster: SumoPredicate =
    Predicate("vehicleOnSameLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOfSameLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle Besides Left Lane Predicates

/** Predicate that checks if there is a vehicle besides the ego vehicle on the left lane. */
val hasVehicleBesidesOnLeftLane: SumoPredicate =
    Predicate("hasVehicleBesidesOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isBesidesOnLeftLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBesideIsSlower: SumoPredicate =
    Predicate("vehicleOnLeftLaneBesideIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneBesideSameSpeed: SumoPredicate =
    Predicate("vehicleOnLeftLaneBesideSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBesideIsFaster: SumoPredicate =
    Predicate("vehicleOnLeftLaneBesideIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle Besides Right Lane Predicates

/** Predicate that checks if there is a vehicle besides the ego vehicle on the right lane. */
val hasVehicleBesidesOnRightLane: SumoPredicate =
    Predicate("hasVehicleBesidesOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isBesidesOnRightLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneBesideIsSlower: SumoPredicate =
    Predicate("vehicleOnRightLaneBesideIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneBesideSameSpeed: SumoPredicate =
    Predicate("vehicleOnRightLaneBesideSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneBesideIsFaster: SumoPredicate =
    Predicate("vehicleOnRightLaneBesideIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }
// endregion

// region Vehicle in Front Left Lane Predicates

/** Predicate that checks if there is a vehicle in front of the ego vehicle on the left lane. */
val hasVehicleInFrontOnLeftLane: SumoPredicate =
    Predicate("hasVehicleInFrontOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isInFrontOnLeftLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneInFrontIsSlower: SumoPredicate =
    Predicate("vehicleOnLeftLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneInFrontSameSpeed: SumoPredicate =
    Predicate("vehicleOnLeftLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneInFrontIsFaster: SumoPredicate =
    Predicate("vehicleOnLeftLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle in Front Right Lane Predicates

/** Predicate that checks if there is a vehicle in front of the ego vehicle on the right lane. */
val hasVehicleInFrontOnRightLane: SumoPredicate =
    Predicate("hasVehicleInFrontOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isInFrontOnRightLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneInFrontIsSlower: SumoPredicate =
    Predicate("vehicleOnRightLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneInFrontSameSpeed: SumoPredicate =
    Predicate("vehicleOnRightLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneInFrontIsFaster: SumoPredicate =
    Predicate("vehicleOnRightLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle in Behind Left Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle on the left lane. */
val hasVehicleInBehindOnLeftLane: SumoPredicate =
    Predicate("hasVehicleInBehindOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isBehindOnLeftLane(otherVehicle, tick.ego) }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBehindIsSlower: SumoPredicate =
    Predicate("vehicleOnLeftLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneBehindSameSpeed: SumoPredicate =
    Predicate("vehicleOnLeftLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBehindIsFaster: SumoPredicate =
    Predicate("vehicleOnLeftLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion

// region Vehicle in Behind Right Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle on the right lane. */
val hasVehicleInBehindOnRightLane: SumoPredicate =
    Predicate("hasVehicleInBehindOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle -> isBehindOnRightLane(otherVehicle, tick.ego) }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneBehindIsSlower: SumoPredicate =
    Predicate("vehicleOnRightLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane(otherVehicle, tick.ego) && isDrivingSlower(otherVehicle, tick.ego)
      }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneBehindSameSpeed: SumoPredicate =
    Predicate("vehicleOnRightLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane(otherVehicle, tick.ego) && isDrivingAtSameSpeed(otherVehicle, tick.ego)
      }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneBehindIsFaster: SumoPredicate =
    Predicate("vehicleOnRightLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane(otherVehicle, tick.ego) && isDrivingFaster(otherVehicle, tick.ego)
      }
    }

// endregion
