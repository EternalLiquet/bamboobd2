# BambooOBD2

An evidence-led, AI-assisted diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S, but the research scope includes European and VAG diagnostic stacks. Work starts with **offline, read-only capture analysis**. No protocol, ECU address, service-reset procedure, or vehicle compatibility has been verified for this car.

There is no diagnostic implementation yet. In particular, this repository contains no vehicle transmit path, reset recipe, ECU flashing support, proprietary dataset, credentials, or raw VIN.

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)

For current scope and evidence, start with [project status](PROJECT_STATUS.md). [Issues](https://github.com/EternalLiquet/bamboobd2/issues) hold the live planning backlog; [pull requests](https://github.com/EternalLiquet/bamboobd2/pulls) show proposed changes. This public repository is sufficient to understand the project.

## First engineering milestone

Define the first Android diagnostic milestone in [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2), including fake transport and sanitized replay acceptance. Establish actual vehicle, adapter, ECU, and transport facts before selecting a protocol library or attempting any live test. See [workflow](docs/WORKFLOW.md) for the issue and verification standard.
