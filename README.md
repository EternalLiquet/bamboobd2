# BambooOBD2

An evidence-led, AI-assisted diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S, but the research scope includes European and VAG diagnostic stacks. Work starts with **offline, read-only capture analysis**. No protocol, ECU address, service-reset procedure, or vehicle compatibility has been verified for this car.

There is no diagnostic implementation yet. In particular, this repository contains no vehicle transmit path, reset recipe, ECU flashing support, proprietary dataset, credentials, or raw VIN.

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)

For project intent and current state, see [`EternalLiquet/gpt-projects-files/bamboobd2`](https://github.com/EternalLiquet/gpt-projects-files/tree/master/bamboobd2). That directory is planning context; this repository is the implementation source of truth.

## First engineering milestone

Define a sanitized capture format and an offline importer/replay harness with fake transport and golden fixtures. Establish actual vehicle, adapter, ECU, and transport facts before selecting a protocol library or attempting any live test. See [workflow](docs/WORKFLOW.md) for the issue and verification standard.
