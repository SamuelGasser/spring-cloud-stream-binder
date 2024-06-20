# Changelog

All notable changes to this project will be documented in this file.

## [4.2.1] - 2024-06-20
### Changed
- Fix sending of Direct Messages

## [4.2.0] - 2024-06-12
### Changed
- By Solace #290 migrate to producer bindings to use JCSMP producer flows
- By Solace #269 give consumer binding threads readable names
- By Solace #294 DATAGO-76828: add transacted producer support
- Bump versions to spring boot 3.3.0 and spring cloud 2023.0.2

## [4.0.1] - 2024-05-13
### Fixed

- Initialisation error of health indicator resulting in a NPE when checking /actuator/health too early.
- Exception on shutdown after trying to reconnect to the broker for some minutes.

## [4.0.0] - 2024-05-07

### Added

- Support for non-persistent publish and subscribe.
- Support for groups in direct subscription using #share subscription on topics. (https://docs.solace.com/Messaging/Direct-Msg/Direct-Messages.htm -> Shared Subscriptions )
- NACK Support for Consumer bindings (by solace https://github.com/SolaceProducts/solace-spring-cloud/pull/270).
- Reapply subscriptions on temporary queues after reconnect with more than 60 sec interruption.

### Fixed

- Startup error with anonymous queues when broker is under load  (fix: https://github.com/SolaceProducts/solace-spring-cloud/issues/266).

### Changed

- Flatten maven structure into a single project (no need for dependency management or starter).
- Upgrade dependencies: 
  - spring-boot: 3.2.5
  - spring-cloud: 2023.0.1
  - sol-jcsmp: 10.23.0
  - others: to latest version
- Deprecated batch messaging processing.

### Removed

- git submodule: solace-integration-test-support (integrated into the test now).
- multimodule with starter (integrated into then main project).

## before 4.0.0

Check forked repository https://github.com/SolaceProducts/solace-spring-cloud