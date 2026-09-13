-- Party vehicles stop being their own entity and become a list on the party.
--
-- They never earned a table of their own. A party vehicle has exactly one attribute -
-- its number - and no life of its own: nothing refers to one except the party that owns
-- it, nobody looks one up on its own, and there is nothing to record against it. What
-- the separate table bought was a Master Data tab, six REST endpoints, a repository and
-- an entity, all to hold a registration number against a party. Production has two
-- parties and one vehicle between them.
--
-- It also cost: the tab could not add a vehicle at all. That endpoint binds the
-- PartyVehicle entity, so `party` has to arrive as an object, and the screen sent the
-- bare id - a 400 every time, for as long as the tab has existed.
--
-- A party now carries its vehicle numbers directly, as an element collection, so several
-- can be recorded and they are edited on the party itself.

CREATE TABLE `party_vehicle_numbers` (
    `party_id`       BIGINT      NOT NULL,
    `vehicle_number` VARCHAR(40) NOT NULL,
    PRIMARY KEY (`party_id`, `vehicle_number`),
    CONSTRAINT `fk_party_vehicle_numbers_party`
        FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT 'Registration numbers of the vehicles a party uses';

-- The number is the key, so a party cannot hold the same vehicle twice. That is the
-- one constraint the old table lacked.
INSERT INTO `party_vehicle_numbers` (`party_id`, `vehicle_number`)
SELECT DISTINCT `party_id`, TRIM(`vehicle_number`)
  FROM `party_vehicles`
 WHERE `vehicle_number` IS NOT NULL
   AND TRIM(`vehicle_number`) <> ''
   AND `party_id` IS NOT NULL;

-- A trading entry records which vehicle carried the load, as the number itself.
--
-- Held as text rather than as a foreign key on purpose: the entry is a record of what
-- happened on a date, and it must keep saying which vehicle came even if that vehicle is
-- later removed from the party. The same reason the message outbox snapshots a phone
-- number instead of joining to the customer.
ALTER TABLE `trading_entries`
    ADD COLUMN `vehicle_number` VARCHAR(40) NULL
        COMMENT 'The vehicle that carried this load, as recorded at the time'
        AFTER `party_id`;

UPDATE `trading_entries` te
  JOIN `party_vehicles` pv ON pv.`id` = te.`party_vehicle_id`
   SET te.`vehicle_number` = pv.`vehicle_number`;

-- The foreign key has to go before the column, and its name is whatever Hibernate
-- generated when the schema was first created - "FK5gc2x9tp2h0vy2ns028pwlkk1" here.
-- Looked up rather than hard-coded: the name is a hash, there is no guarantee dev, test
-- and production were created by the same Hibernate version, and MySQL has no portable
-- DROP FOREIGN KEY IF EXISTS to fall back on.
SET @fk := (
    SELECT CONSTRAINT_NAME
      FROM information_schema.KEY_COLUMN_USAGE
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'trading_entries'
       AND COLUMN_NAME = 'party_vehicle_id'
       AND REFERENCED_TABLE_NAME = 'party_vehicles'
     LIMIT 1
);

SET @sql := IF(@fk IS NULL,
               'SELECT "no foreign key on party_vehicle_id to drop"',
               CONCAT('ALTER TABLE `trading_entries` DROP FOREIGN KEY `', @fk, '`'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `trading_entries`
    DROP COLUMN `party_vehicle_id`;

DROP TABLE `party_vehicles`;
