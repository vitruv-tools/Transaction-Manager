# Transaction-Manager

[![GitHub Action CI](https://github.com/vitruv-tools/Transaction-Manager/actions/workflows/ci.yml/badge.svg)](https://github.com/vitruv-tools/Transaction-Manager/actions/workflows/ci.yml)
[![Issues](https://img.shields.io/github/issues/vitruv-tools/Transaction-Manager.svg)](https://github.com/vitruv-tools/Transaction-Manager/issues)
[![License](https://img.shields.io/github/license/vitruv-tools/Transaction-Manager.svg)](https://raw.githubusercontent.com/vitruv-tools/Transaction-Manager/main/LICENSE)

[Vitruvius](https://vitruv.tools) is a framework for view-based (software) development.
It assumes different models to be used for describing a system, which are automatically kept consistent by the framework executing (semi-)automated rules that preserve consistency.
These models are modified only via views, which are projections from the underlying models.
For general information on Vitruvius, see our [GitHub Organisation](https://github.com/vitruv-tools) and our [Wiki](https://github.com/vitruv-tools/.github/wiki).

The Transaction-Manager project provides a component to concurrently apply changes to the underlying models of a V-SUM.
Changes are treated as transactions, and must fulfill the guarantees of atomicity, consistency, and isolation.
For isolation, Two-Phase Locking with suitable locks for each atomic change is supported.
Consistency is checked with consistency rules written in VitruvOCL.
Atomicity is ensured by rolling back transactions upon failure or inconsistencies, and applying the correct inverse changes.

## Framework-internal Dependencies

This project depends on the following other projects from the Vitruvius framework:

- [Vitruv-Change](https://github.com/vitruv-tools/Vitruv-Change)
- [Vitruv-DSLs](https://github.com/vitruv-tools/Vitruv-DSLs)
- [Vitruv](https://github.com/vitruv-tools/Vitruv)

## Module Overview

| **Name**     | **Description**                                             |
|--------------|-------------------------------------------------------------|
| management   | Main classes to management transactions.                    |
| - scheduling | Definition of transaction execution threads and schedulers. |
| - locking    | Lock management and locking schedulers.                     |

## Setup

This project uses [Git Submodules](https://git-scm.com/book/en/v2/Git-Tools-Submodules).
When cloning this project, you need to run:

```sh
user@pc $git submodule init
user@pc $git submodule update
```

To pull updates from this repository and its submodules, run:

```sh
user@pc $git pull --recurse-submodules
```

To make updates to a submodule, run:

```sh
user@pc submodule/ $git push --recurse-submodules=on-demand
```
