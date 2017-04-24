package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.{ EphemeralVolume, HostVolume, Volume => PodVolume }
import mesosphere.marathon.state.{ DiskType, ExternalVolumeInfo, PersistentVolumeInfo }
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => Mesos }

trait VolumeConversion extends ConstraintConversion with DefaultConversions {

  implicit val volumeRamlReader: Reads[Volume, PodVolume] = Reads { v =>
    v.host match {
      case Some(hostPath) => HostVolume(v.name, hostPath)
      case None => EphemeralVolume(v.name)
    }
  }

  implicit val volumeRamlWriter: Writes[PodVolume, Volume] = Writes {
    case e: EphemeralVolume => Volume(e.name)
    case h: HostVolume => Volume(h.name, Some(h.hostPath))
  }

  implicit val volumeModeWrites: Writes[Mesos.Volume.Mode, ReadMode] = Writes {
    case Mesos.Volume.Mode.RO => ReadMode.Ro
    case Mesos.Volume.Mode.RW => ReadMode.Rw
  }

  implicit val volumeModeReads: Reads[ReadMode, Mesos.Volume.Mode] = Reads {
    case ReadMode.Ro => Mesos.Volume.Mode.RO
    case ReadMode.Rw => Mesos.Volume.Mode.RW
  }

  implicit val volumeWrites: Writes[state.Volume, AppVolume] = Writes { volume =>

    implicit val externalVolumeWrites: Writes[state.ExternalVolumeInfo, ExternalVolume] = Writes { ev =>
      ExternalVolume(size = ev.size, name = Some(ev.name), provider = Some(ev.provider), options = ev.options)
    }

    implicit val persistentVolumeInfoWrites: Writes[state.PersistentVolumeInfo, PersistentVolume] = Writes { pv =>
      val pvType = Option(pv.`type` match {
        case DiskType.Mount => PersistentVolumeType.Mount
        case DiskType.Path => PersistentVolumeType.Path
        case DiskType.Root => PersistentVolumeType.Root
      })
      PersistentVolume(pvType, pv.size, pv.maxSize, pv.constraints.toRaml[Set[Seq[String]]])
    }

    volume match {
      case dv: state.DockerVolume => AppDockerVolume(
        volume.containerPath,
        Some(dv.hostPath),
        mode = volume.mode.toRaml)
      case ev: state.ExternalVolume => AppExternalVolume(
        volume.containerPath,
        external = ev.external.toRaml,
        mode = volume.mode.toRaml)
      case pv: state.PersistentVolume => AppPersistentVolume(
        volume.containerPath,
        persistent = pv.persistent.toRaml,
        mode = volume.mode.toRaml)
    }
  }

  implicit val volumeReads: Reads[AppVolume, state.Volume] = Reads { vol =>
    def failed[T](msg: String): T =
      throw SerializationFailedException(msg)

    val result: state.Volume = vol match {
      case AppExternalVolume(ctPath, Some(hostPath), external, mode) =>
        val info = ExternalVolumeInfo(
          size = external.size,
          name = external.name.getOrElse(failed("external volume requires a name")),
          provider = external.provider.getOrElse(failed("external volume requires a provider")),
          options = external.options
        )
        state.ExternalVolume(containerPath = ctPath, external = info, mode = mode.fromRaml)
      case AppPersistentVolume(ctPath, hostPath, persistent, mode) =>
        val volType = persistent.`type` match {
          case Some(definedType) => definedType match {
            case PersistentVolumeType.Root => DiskType.Root
            case PersistentVolumeType.Mount => DiskType.Mount
            case PersistentVolumeType.Path => DiskType.Path
          }
          case None => DiskType.Root
        }
        val info = PersistentVolumeInfo(
          size = persistent.size,
          maxSize = persistent.maxSize,
          `type` = volType,
          constraints = persistent.constraints.map { constraint =>
            (constraint.headOption, constraint.lift(1), constraint.lift(2)) match {
              case (Some("path"), Some("LIKE"), Some(value)) =>
                Protos.Constraint.newBuilder()
                  .setField("path")
                  .setOperator(Protos.Constraint.Operator.LIKE)
                  .setValue(value)
                  .build()
              case _ =>
                throw SerializationFailedException(s"illegal volume constraint ${constraint.mkString(",")}")
            }
          }(collection.breakOut)
        )
        state.PersistentVolume(containerPath = ctPath, persistent = info, mode = mode.fromRaml)
      case AppSecretVolume(secret: SecretDef) =>
        ??? // TODO: Provide implementation
      case AppDockerVolume(ctPath, hostPath, mode) =>
        state.DockerVolume(containerPath = ctPath, hostPath = hostPath.getOrElse(""), mode = mode.fromRaml)
      case v => failed(s"illegal volume specification $v")
    }
    result
  }

  implicit val appVolumeExternalProtoRamlWriter: Writes[Protos.Volume.ExternalVolumeInfo, ExternalVolume] = Writes { vol =>
    ExternalVolume(
      size = vol.when(_.hasSize, _.getSize).orElse(ExternalVolume.DefaultSize),
      name = vol.when(_.hasName, _.getName).orElse(ExternalVolume.DefaultName),
      provider = vol.when(_.hasProvider, _.getProvider).orElse(ExternalVolume.DefaultProvider),
      options = vol.whenOrElse(_.getOptionsCount > 0, _.getOptionsList.map { x => x.getKey -> x.getValue }(collection.breakOut), ExternalVolume.DefaultOptions)
    )
  }

  implicit val appPersistentVolTypeProtoRamlWriter: Writes[Mesos.Resource.DiskInfo.Source.Type, PersistentVolumeType] = Writes { typ =>
    import Mesos.Resource.DiskInfo.Source.Type._
    typ match {
      case MOUNT => PersistentVolumeType.Mount
      case PATH => PersistentVolumeType.Path
      case badType => throw new IllegalStateException(s"unsupported Mesos resource disk-info source type $badType")
    }
  }

  implicit val appVolumePersistentProtoRamlWriter: Writes[Protos.Volume.PersistentVolumeInfo, PersistentVolume] = Writes { vol =>
    PersistentVolume(
      `type` = vol.when(_.hasType, _.getType.toRaml).orElse(PersistentVolume.DefaultType),
      size = vol.getSize,
      maxSize = vol.when(_.hasMaxSize, _.getMaxSize).orElse(PersistentVolume.DefaultMaxSize), // TODO(jdef) protobuf serialization is broken for this
      constraints = vol.whenOrElse(_.getConstraintsCount > 0, _.getConstraintsList.map(_.toRaml[Seq[String]])(collection.breakOut), PersistentVolume.DefaultConstraints)
    )
  }

  implicit val appDockerVolumeProtoRamlWriter: Writes[Protos.Volume, AppVolume] = Writes {
    case vol if vol.hasExternal => AppExternalVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.when(_.hasHostPath, _.getHostPath).orElse(AppDockerVolume.DefaultHostPath),
      external = vol.getExternal.toRaml,
      mode = vol.getMode.toRaml
    )
    case vol if vol.hasPersistent => AppPersistentVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.when(_.hasHostPath, _.getHostPath).orElse(AppDockerVolume.DefaultHostPath),
      persistent = vol.getPersistent.toRaml,
      mode = vol.getMode.toRaml
    )
    case vol => AppDockerVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.when(_.hasHostPath, _.getHostPath).orElse(AppDockerVolume.DefaultHostPath),
      mode = vol.getMode.toRaml
    )
    // TODO adju secrets?
  }
}

object VolumeConversion extends VolumeConversion
