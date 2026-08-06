# Installation Windows de NEXUS

## Livrables

La release Windows x64 produit :

```text
target\dist\nexus-context-engine-0.2.0-windows-x64.zip
target\dist\nexus-context-engine-0.2.0-windows-x64.zip.sha256
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
```

Le ZIP et le setup embarquent un runtime Java : aucune JVM système n'est requise pour exécuter NEXUS après installation.

## Générer un candidat Windows local

Prérequis de build :

- Windows x64 ;
- `JAVA_HOME` pointant vers un JDK 21 ou supérieur ;
- accès réseau la première fois si Inno Setup n'est pas déjà disponible.

Depuis une console Windows PowerShell déjà ouverte à la racine du repository, activez le bypass uniquement pour le processus courant puis exécutez directement les scripts. Cette forme ne dépend pas de la présence de `powershell.exe` dans le `PATH` et ne modifie pas la stratégie d'exécution de façon permanente :

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& .\scripts\release\build-windows-release.ps1
```

Le script :

1. exécute le reactor Maven et les tests ;
2. dérive les modules Java requis avec `jdeps` ;
3. produit une `app-image` `jpackage` avec runtime embarqué ;
4. vérifie `nexus --version --json` sur cette image ;
5. produit le ZIP Windows et son SHA-256 ;
6. télécharge si nécessaire une version épinglée d'Inno Setup, vérifie sa signature Authenticode puis utilise `ISCC.exe` ;
7. produit le setup `.exe` et son SHA-256.

Pour une itération de packaging plus rapide après avoir déjà qualifié le code :

```powershell
& .\scripts\release\build-windows-release.ps1 -SkipVerify
```

`-SkipVerify` ne doit pas être utilisé comme preuve de release finale.

## Tests locaux complets

Dans la même console PowerShell, après le `Set-ExecutionPolicy -Scope Process Bypass -Force` ci-dessus :

Qualification standard NEXUS :

```powershell
& .\scripts\validate-phase-6.ps1
```

Benchmark scale complet :

```powershell
& .\scripts\measure-scale-regression.ps1 -Profile full
```

Qualification spécifique du setup après génération :

```powershell
$setup = Resolve-Path .\target\dist\.smoke\NEXUS-0.2.0-windows-x64-smoke-setup.exe
& .\scripts\release\test-windows-installer.ps1 -Setup $setup
```

La CI `Windows Installer` construit automatiquement cette variante smoke, l'installe dans un répertoire isolé, exécute la CLI puis lance le désinstallateur.

## Installation utilisateur

Lancer :

```text
NEXUS-0.2.0-windows-x64-setup.exe
```

Le setup est current-user et ne nécessite pas de privilèges administrateur. Par défaut il installe sous :

```text
%LOCALAPPDATA%\Programs\NEXUS
```

L'option `Ajouter NEXUS au PATH de l'utilisateur` permet ensuite :

```powershell
nexus --version
nexus --help
```

Un nouveau terminal peut être nécessaire pour observer la mise à jour du `PATH`.

## Désinstallation et données

Le désinstallateur Windows est enregistré normalement dans les Applications installées et sous le répertoire d'installation.

La désinstallation :

- supprime les fichiers applicatifs installés ;
- retire du `PATH` uniquement l'entrée gérée par le setup NEXUS ;
- ne supprime pas `NEXUS_HOME`.

Ce choix permet upgrade, désinstallation puis réinstallation sans perdre la base SQLite canonique et les données locales de l'utilisateur. Une suppression des données doit rester une action explicite et séparée.

## Intégrité

Vérifier un setup avant installation :

```powershell
$actual = (Get-FileHash .\target\dist\NEXUS-0.2.0-windows-x64-setup.exe -Algorithm SHA256).Hash.ToLowerInvariant()
Get-Content .\target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
$actual
```

Les distributions embarquent également `LICENSE`, `THIRD_PARTY_NOTICES.txt`, `SBOM.cdx.json`, `VERSION` et `RUNTIME-MODULES.txt`.
