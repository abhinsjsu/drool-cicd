## How do I run these integration tests ##

This test package runs using the Hydra Test Platform.

### Run your Tests ###
* To run against a local server, select any target test from buid.xml file. e.g.
```
bb development-integ-onboarding
```
* To run changes to your integ test against your Beta stack. If you have an external DNS on your service, you can simply update your testng xml. Otherwise subscribe to
hydra-interest@ to find out when we announce our CLI that will let you trigger hydra tests from the workspace.

### To Run In Pipelines ###
A pipeline approval step was automatically defined in your Service LPT Package, every change through pipelines will automatically be approved by this step.

## FAQ ##
* How do I set TestNG Parameters for my test?

Parameters are defined in two places, in your `src/testng-development.xml` file for local testing and in the [Hydra Run Definition](https://w.amazon.com/bin/view/HydraTestPlatform/Onboarding/#HCreateyourRunDefinition) defined in your Service LPT.
