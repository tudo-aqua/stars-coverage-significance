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
import tools.aqua.stars.core.evaluation.Predicate.Companion.predicate
import tools.aqua.stars.core.evaluation.VariablePredicate.Companion.predicate
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.firstorder.exists

typealias SumoPredicate = Predicate<TimeStep>

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
const val SPEED_THRESHOLD_KMH: Double = 15.0

/** Predicate to check if the ego vehicle is on the left lane. */
val isOnLeftLane =
    predicate<TimeStep>("isOnLeftLane") { tick ->
      tick.ego.currentLane.laneIndex == LANE_INDEX_LEFT
    }

/** Predicate to check if the ego vehicle is on the middle lane. */
val isOnMiddleLane =
    predicate<TimeStep>("isOnMiddleLane") { tick ->
      tick.ego.currentLane.laneIndex == LANE_INDEX_MIDDLE
    }

/** Predicate to check if the ego vehicle is on the right lane. */
val isOnRightLane =
    predicate<TimeStep>("isOnRightLane") { tick ->
      tick.ego.currentLane.laneIndex == LANE_INDEX_RIGHT
    }

/** Predicate to determine if another vehicle is slower than the ego vehicle. */
val isDrivingFaster =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Driving Faster") { _, (other, ego) ->
      val diff = other.speedKmPerHour.toDouble() - ego.speedKmPerHour.toDouble()
      diff > SPEED_THRESHOLD_KMH
    }

/** Predicate to determine if there is no vehicle between the ego vehicle and another vehicle. */
val noVehicleBetweenOnSameLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("No Vehicle Between On Same Lane") {
        tick,
        (ego, other) ->
      tick.vehiclesInTick.none { betweenVehicle ->
        betweenVehicle != ego &&
            betweenVehicle != other &&
            isInFrontOnSameLane.holds(tick, betweenVehicle to ego) &&
            isBehindOnSameLane.holds(tick, betweenVehicle to other)
      }
    }

/**
 * Predicate to determine if there is no vehicle between the ego vehicle and another vehicle on the
 * left lane.
 */
val noVehicleBetweenOnLeftLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("No Vehicle Between On Left Lane") {
        tick,
        (ego, other) ->
      tick.vehiclesInTick.none { betweenVehicle ->
        betweenVehicle != ego &&
            betweenVehicle != other &&
            isInFrontOnLeftLane.holds(tick, betweenVehicle to ego) &&
            isBehindOnLeftLane.holds(tick, betweenVehicle to other)
      }
    }

/**
 * Predicate to determine if there is no vehicle between the ego vehicle and another vehicle on the
 * right lane.
 */
val noVehicleBetweenOnRightLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("No Vehicle Between On Right Lane") {
        tick,
        (ego, other) ->
      tick.vehiclesInTick.none { betweenVehicle ->
        betweenVehicle != ego &&
            betweenVehicle != other &&
            isInFrontOnRightLane.holds(tick, betweenVehicle to ego) &&
            isBehindOnRightLane.holds(tick, betweenVehicle to other)
      }
    }

/**
 * Helper predicate to determine if another vehicle is driving at about the same speed as the ego
 * vehicle.
 */
val isDrivingAtSameSpeed =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Driving At Same Speed") { _, (other, ego) ->
      val diff = abs(other.speedKmPerHour.toDouble() - ego.speedKmPerHour.toDouble())
      diff <= SPEED_THRESHOLD_KMH
    }

/** Helper predicate to determine if another vehicle is slower than the ego vehicle. */
val isDrivingSlower =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Driving Slower") { _, (other, ego) ->
      val diff = other.speedKmPerHour.toDouble() - ego.speedKmPerHour.toDouble()
      diff < -SPEED_THRESHOLD_KMH
    }

/** Helper predicate to determine if two vehicles are on the same lane. */
val isOnSameLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is On Same Lane") { _, (other, ego) ->
      other.currentLane == ego.currentLane
    }

/** Helper predicate to determine if another vehicle is on the left lane of the ego vehicle. */
val isOnLeftLaneOf =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is On Left Lane Of") { _, (otherVehicle, ego) ->
      otherVehicle.currentLane.laneIndex == ego.currentLane.laneIndex + 1
    }

/** Helper predicate to determine if another vehicle is on the right lane of the ego vehicle. */
val isOnRightLaneOf =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is On Right Lane Of") { _, (otherVehicle, ego) ->
      otherVehicle.currentLane.laneIndex == ego.currentLane.laneIndex - 1
    }

/** Helper predicate to determine if another vehicle is in front of the ego vehicle. */
val isInFrontOf =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is In Front Of") { _, (otherVehicle, ego) ->
      otherVehicle.positionOnLaneMeters > ego.positionOnLaneMeters &&
          (otherVehicle.positionOnLaneMeters in
              (ego.positionOnLaneMeters + VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM)..(ego
                      .positionOnLaneMeters + VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO))
    }

/**
 * Helper predicate to determine if another vehicle is in front of the ego vehicle without distance
 * constraints.
 */
val isInFrontOfAbsolute =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is In Front Of Absolute") { _, (otherVehicle, ego)
      ->
      otherVehicle.positionOnLaneMeters > ego.positionOnLaneMeters
    }

/** Helper predicate to determine if another vehicle is behind the ego vehicle. */
val isBehindOf =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Behind Of") { _, (otherVehicle, ego) ->
      otherVehicle.positionOnLaneMeters < ego.positionOnLaneMeters &&
          (otherVehicle.positionOnLaneMeters in
              ((ego.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO)..ego
                      .positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM))
    }

/** Helper predicate to determine if another vehicle is besides the ego vehicle. */
val isBesidesOf =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Besides Of") { _, (otherVehicle, ego) ->
      otherVehicle.positionOnLaneMeters in
          (ego.positionOnLaneMeters - VEHICLE_BESIDES_MAX_DISTANCE_METERS)..(ego
                  .positionOnLaneMeters + VEHICLE_BESIDES_MAX_DISTANCE_METERS)
    }

/**
 * Helper predicate to determine if another vehicle is in front of the ego vehicle on the same lane.
 */
val isInFrontOnSameLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is In Front Of Same Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnSameLane.holds(tick, otherVehicle to ego) && isInFrontOf.holds(tick, otherVehicle to ego)
    }

/** Helper predicate to determine if another vehicle is behind the ego vehicle on the same lane. */
val isBehindOnSameLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Behind Of Same Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnSameLane.holds(tick, otherVehicle to ego) && isBehindOf.holds(tick, otherVehicle to ego)
    }

/** Helper predicate to determine if another vehicle is besides the ego vehicle on the left lane. */
val isBesidesOnLeftLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Besides On Left Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnLeftLaneOf.holds(tick, otherVehicle to ego) &&
          isBesidesOf.holds(tick, otherVehicle to ego)
    }

/**
 * Helper predicate to determine if another vehicle is besides the ego vehicle on the right lane.
 */
val isBesidesOnRightLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Besides On Right Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnRightLaneOf.holds(tick, otherVehicle to ego) &&
          isBesidesOf.holds(tick, otherVehicle to ego)
    }

/**
 * Helper predicate to determine if another vehicle is in front of the ego vehicle on the left lane.
 */
val isInFrontOnLeftLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is In Front On Left Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnLeftLaneOf.holds(tick, otherVehicle to ego) &&
          isInFrontOf.holds(tick, otherVehicle to ego)
    }

/**
 * Helper predicate to determine if another vehicle is in front of the ego vehicle on the right
 * lane.
 */
val isInFrontOnRightLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is In Front On Right Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnRightLaneOf.holds(tick, otherVehicle to ego) &&
          isInFrontOf.holds(tick, otherVehicle to ego)
    }

/** Helper predicate to determine if another vehicle is behind the ego vehicle on the left lane. */
val isBehindOnLeftLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Behind On Left Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnLeftLaneOf.holds(tick, otherVehicle to ego) && isBehindOf.holds(tick, otherVehicle to ego)
    }

/** Helper predicate to determine if another vehicle is behind the ego vehicle on the right lane. */
val isBehindOnRightLane =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is Behind On Right Lane") {
        tick,
        (otherVehicle, ego) ->
      isOnRightLaneOf.holds(tick, otherVehicle to ego) &&
          isBehindOf.holds(tick, otherVehicle to ego)
    }

// region Vehicle in Front Same Lane Predicates

/**
 * Predicate that checks if there is a vehicle in front of the ego vehicle within a certain
 * distance.
 */
val hasVehicleInFrontOnSameLane =
    predicate<TimeStep>("hasVehicleInFrontOnSameLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is slower than the ego vehicle.
 */
val vehicleOnSameLaneInFrontIsSlower =
    predicate<TimeStep>("vehicleOnSameLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, tick.ego to otherVehicle) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is moving at about the same
 * speed as the ego vehicle.
 */
val vehicleOnSameLaneInFrontSameSpeed =
    predicate<TimeStep>("vehicleOnSameLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, tick.ego to otherVehicle) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is faster than the ego vehicle.
 */
val vehicleOnSameLaneInFrontIsFaster =
    predicate<TimeStep>("vehicleOnSameLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, tick.ego to otherVehicle) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle Behind Same Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle within a certain distance. */
val hasVehicleBehindOnSameLane =
    predicate<TimeStep>("hasVehicleBehindOnSameLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnSameLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is slower than the
 * ego vehicle.
 */
val vehicleOnSameLaneBehindIsSlower =
    predicate<TimeStep>("vehicleOnSameLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is moving at about
 * the same speed as the ego vehicle.
 */
val vehicleOnSameLaneBehindSameSpeed =
    predicate<TimeStep>("vehicleOnSameLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the same lane the ego vehicle is faster than the
 * ego vehicle.
 */
val vehicleOnSameLaneBehindIsFaster =
    predicate<TimeStep>("vehicleOnSameLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            noVehicleBetweenOnSameLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle Besides Left Lane Predicates

/** Predicate that checks if there is a vehicle besides the ego vehicle on the left lane. */
val hasVehicleBesidesOnLeftLane =
    predicate<TimeStep>("hasVehicleBesidesOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBesideIsSlower =
    predicate<TimeStep>("vehicleOnLeftLaneBesideIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneBesideSameSpeed =
    predicate<TimeStep>("vehicleOnLeftLaneBesideSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBesideIsFaster =
    predicate<TimeStep>("vehicleOnLeftLaneBesideIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle Besides Right Lane Predicates

/** Predicate that checks if there is a vehicle besides the ego vehicle on the right lane. */
val hasVehicleBesidesOnRightLane =
    predicate<TimeStep>("hasVehicleBesidesOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneBesideIsSlower =
    predicate<TimeStep>("vehicleOnRightLaneBesideIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneBesideSameSpeed =
    predicate<TimeStep>("vehicleOnRightLaneBesideSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle besides on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneBesideIsFaster =
    predicate<TimeStep>("vehicleOnRightLaneBesideIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBesidesOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }
// endregion

// region Vehicle in Front Left Lane Predicates

/** Predicate that checks if there is a vehicle in front of the ego vehicle on the left lane. */
val hasVehicleInFrontOnLeftLane =
    predicate<TimeStep>("hasVehicleInFrontOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneInFrontIsSlower =
    predicate<TimeStep>("vehicleOnLeftLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneInFrontSameSpeed =
    predicate<TimeStep>("vehicleOnLeftLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneInFrontIsFaster =
    predicate<TimeStep>("vehicleOnLeftLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle in Front Right Lane Predicates

/** Predicate that checks if there is a vehicle in front of the ego vehicle on the right lane. */
val hasVehicleInFrontOnRightLane =
    predicate<TimeStep>("hasVehicleInFrontOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneInFrontIsSlower =
    predicate<TimeStep>("vehicleOnRightLaneInFrontIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneInFrontSameSpeed =
    predicate<TimeStep>("vehicleOnRightLaneInFrontSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle in front on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneInFrontIsFaster =
    predicate<TimeStep>("vehicleOnRightLaneInFrontIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isInFrontOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle in Behind Left Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle on the left lane. */
val hasVehicleInBehindOnLeftLane =
    predicate<TimeStep>("hasVehicleInBehindOnLeftLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBehindIsSlower =
    predicate<TimeStep>("vehicleOnLeftLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnLeftLaneBehindSameSpeed =
    predicate<TimeStep>("vehicleOnLeftLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }

/**
 * Predicate that checks if the vehicle behind on the left lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnLeftLaneBehindIsFaster =
    predicate<TimeStep>("vehicleOnLeftLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnLeftLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion

// region Vehicle in Behind Right Lane Predicates

/** Predicate that checks if there is a vehicle behind the ego vehicle on the right lane. */
val hasVehicleInBehindOnRightLane =
    predicate<TimeStep>("hasVehicleInBehindOnRightLane") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane.holds(tick, otherVehicle to tick.ego)
      }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is slower than
 * the ego vehicle.
 */
val vehicleOnRightLaneBehindIsSlower =
    predicate<TimeStep>("vehicleOnRightLaneBehindIsSlower") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingSlower.holds(tick, otherVehicle to tick.ego)
      }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is moving at
 * about the same speed as the ego vehicle.
 */
val vehicleOnRightLaneBehindSameSpeed =
    predicate<TimeStep>("vehicleOnRightLaneBehindSameSpeed") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingAtSameSpeed.holds(tick, otherVehicle to tick.ego)
      }
    }
/**
 * Predicate that checks if the vehicle behind on the right lane of the ego vehicle is faster than
 * the ego vehicle.
 */
val vehicleOnRightLaneBehindIsFaster =
    predicate<TimeStep>("vehicleOnRightLaneBehindIsFaster") { tick ->
      exists(tick.vehiclesInTick) { otherVehicle ->
        isBehindOnRightLane.holds(tick, otherVehicle to tick.ego) &&
            isDrivingFaster.holds(tick, otherVehicle to tick.ego)
      }
    }

// endregion
