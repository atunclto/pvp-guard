# PvPGuard (Paper 1.21.5)

Plugin que otorga protección de PvP a un jugador que haya sufrido **N** muertes por otros jugadores dentro de **M** minutos. 
Por defecto, N=3 y M=15. La protección dura **15** minutos y puede desactivarse con `/proteger off`.

## Build
- Requiere Java 21 y Maven.
- Compila con:
```bash
mvn -q -U -e -B clean package
```
El JAR quedará en `target/pvp-guard-1.0.0-all.jar`.

## Configuración (`config.yml`)
- `threshold_kills`: muertes PvP necesarias para activar protección.
- `window_minutes`: ventana de tiempo para el conteo.
- `protection_minutes`: duración de la protección.
- `disable_damage_both_ways`: si `true`, anula daño si atacante o víctima está protegido; si `false`, solo protege a la víctima.

## Comando
- `/proteger off` — desactiva tu protección actual.

## Notas
- Se consideran muertes PvP cuando `PlayerDeathEvent` tiene `getKiller() != null` (otro jugador).
- El daño PvP se bloquea en `EntityDamageByEntityEvent`.
