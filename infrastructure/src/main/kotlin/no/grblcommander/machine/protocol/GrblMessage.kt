package no.grblcommander.machine.protocol

sealed interface GrblMessage {
    data object Ok : GrblMessage
    data class Error(val code: Int) : GrblMessage
    data class Alarm(val code: Int) : GrblMessage
    data class AlarmDescription(val code: Int, val description: String) : GrblMessage
    data class Startup(val text: String) : GrblMessage
    data class Status(val report: ControllerStatus) : GrblMessage
    data class Information(val text: String) : GrblMessage
}
