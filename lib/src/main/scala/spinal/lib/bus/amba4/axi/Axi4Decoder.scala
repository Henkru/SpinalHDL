package spinal.lib.bus.amba4.axi

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc.SizeMapping

case class Axi4ReadOnlyDecoder(axiConfig: Axi4Config,decodings : Iterable[SizeMapping],pendingMax : Int = 7) extends Component{
  val io = new Bundle{
    val input = slave(Axi4ReadOnly(axiConfig))
    val outputs = Vec(master(Axi4ReadOnly(axiConfig)),decodings.size)
  }

  val pendingCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = io.input.readCmd.fire,
    decWhen = io.input.readRsp.fire && io.input.readRsp.last
  )

  val decodedSels,appliedSels = Bits(decodings.size bits)
  val lastCmdSel = RegNextWhen(appliedSels,io.input.readCmd.ready)  init(0)
  val allowCmd = pendingCounter =/= pendingMax && (pendingCounter === 0  || (lastCmdSel === decodedSels))
  decodedSels  := decodings.map(_.hit(io.input.readCmd.addr) && io.input.readCmd.valid).asBits
  appliedSels := allowCmd ? decodedSels | 0

  //Wire readCmd
  io.input.readCmd.ready := (appliedSels & io.outputs.map(_.readCmd.ready).asBits).orR
  for((output,sel) <- (io.outputs,appliedSels.asBools).zipped){
    output.readCmd.valid := sel
    output.readCmd.payload := io.input.readCmd.payload
  }

  //Wire ReadRsp
  val readRspIndex = OHToUInt(lastCmdSel)
  io.input.readRsp.valid := io.outputs.map(_.readRsp.valid).asBits.orR
  io.input.readRsp.payload := io.outputs(readRspIndex).readRsp.payload
  io.outputs.foreach(_.readRsp.ready := io.input.readRsp.ready)

  //Decoding error managment
  val decodingErrorPossible = decodings.map(_.size).sum < (BigInt(1) << axiConfig.addressWidth)
  val decodingError = if(decodingErrorPossible) new Area{
    val detected = decodedSels === 0 && io.input.readCmd.valid
    val sendRsp = RegInit(False)
    val id = if(axiConfig.useId) Reg(UInt(axiConfig.idWidth bits)) else null
    val remaining = Reg(UInt(8 bits))
    val remainingZero = remaining === 0

    //Wait until all pending commands are done
    when(detected && pendingCounter === 0){
      io.input.readCmd.ready := True
      if(id != null)id := io.input.readCmd.id
      remaining := (if(axiConfig.useLen) io.input.readCmd.len else U(0))
      sendRsp := True
    }

    //Send a DECERR readRsp
    when(sendRsp) {
      io.input.readRsp.valid := True
      if(axiConfig.useResp) io.input.readRsp.setDECERR
      if(id != null) io.input.readRsp.id := id
      if(io.input.readRsp.last != null) io.input.readRsp.last := remainingZero
      when(io.input.readRsp.ready) {
        remaining := remaining - 1
        when(remainingZero) {
          sendRsp := False
        }
      }
      allowCmd := False
    }
  }
}


case class Axi4WriteOnlyDecoder(axiConfig: Axi4Config,decodings : Iterable[SizeMapping],pendingMax : Int = 7) extends Component{
  val io = new Bundle{
    val input = slave(Axi4WriteOnly(axiConfig))
    val outputs = Vec(master(Axi4WriteOnly(axiConfig)),decodings.size)
  }

  //Used to allow writeData to be transmited before or in the same time than the corresponding writeCmd
  val dataOvertakeCmdEvent = False
  val dataOvertakeCmd = RegInit(False)  clearWhen(io.input.writeCmd.fire) setWhen(dataOvertakeCmdEvent)


  val pendingCmdCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = io.input.writeCmd.fire,
    decWhen = io.input.writeRsp.fire
  )

  val pendingDataCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = (io.input.writeCmd.fire && !dataOvertakeCmd) || dataOvertakeCmdEvent,
    decWhen = io.input.writeData.fire && io.input.writeData.last
  )


  val decodedCmdSels,appliedCmdSels = Bits(decodings.size bits)
  val lastCmdSels  = RegNextWhen(appliedCmdSels,io.input.writeCmd.ready)  init(0)
  val allowCmd    = pendingCmdCounter =/= pendingMax && (pendingCmdCounter === 0  || (lastCmdSels === decodedCmdSels))
  val allowData   = pendingDataCounter =/= 0 || (io.input.writeCmd.valid && ! dataOvertakeCmd)
  decodedCmdSels := decodings.map(_.hit(io.input.writeCmd.addr) && io.input.writeCmd.valid).asBits
  appliedCmdSels := allowCmd ? decodedCmdSels | 0

  when(io.input.writeCmd.fire){
    dataOvertakeCmd := False
  }
  when(io.input.writeData.fire && !io.input.writeCmd.ready && pendingCmdCounter === 0 && !dataOvertakeCmd){
    dataOvertakeCmdEvent := True
  }

  
  //Wire writeCmd
  io.input.writeCmd.ready := (appliedCmdSels & io.outputs.map(_.writeCmd.ready).asBits).orR
  for((output,sel) <- (io.outputs,appliedCmdSels.asBools).zipped){
    output.writeCmd.valid := sel
    output.writeCmd.payload := io.input.writeCmd.payload
  }

  //Wire writeData
  val writeDataSels = (lastCmdSels | appliedCmdSels)
  io.input.writeData.ready := (writeDataSels & io.outputs.map(_.writeData.ready).asBits).orR && allowData
  for((output,writeDataSel) <- (io.outputs,writeDataSels.asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && allowData && writeDataSel
    output.writeData.payload := io.input.writeData.payload
  }

  //Wire writeRsp
  val writeRspIndex = OHToUInt(lastCmdSels)
  io.input.writeRsp.valid := io.outputs.map(_.writeRsp.valid).asBits.orR
  io.input.writeRsp.payload := io.outputs(writeRspIndex).writeRsp.payload
  io.outputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)


  //Decoding error managment
  val decodingErrorPossible = decodings.map(_.size).sum < (BigInt(1) << axiConfig.addressWidth)
  val decodingError = if(decodingErrorPossible) new Area{
    val detected = decodedCmdSels === 0 && io.input.writeCmd.valid
    val waitDataLast = RegInit(False)
    val sendRsp = RegInit(False)
    val id = if(axiConfig.useId) Reg(UInt(axiConfig.idWidth bits)) else null

    //Wait until all pending commands are done
    when(detected && pendingCmdCounter === 0){
      io.input.writeCmd.ready := True
      if(id != null) id := io.input.writeCmd.id
      waitDataLast := True
    }

    //Consume all writeData transaction
    when(waitDataLast){
      io.input.writeData.ready := True
      when(pendingDataCounter === 0){
        io.input.writeData.ready := False
        waitDataLast := False
        sendRsp := True
      }
      allowCmd := False
    }

    //Send a DECERR writeRsp
    when(sendRsp) {
      io.input.writeRsp.valid := True
      if(axiConfig.useResp) io.input.writeRsp.setDECERR
      if(id != null) io.input.writeRsp.id := id
      when(io.input.writeRsp.ready) {
        sendRsp := False
      }
      allowCmd := False
    }
  }
}



case class Axi4SharedDecoder(axiConfig: Axi4Config,
                             readDecodings : Iterable[SizeMapping],
                             writeDecodings : Iterable[SizeMapping],
                             sharedDecodings : Iterable[SizeMapping],
                             pendingMax : Int = 7) extends Component{
  val io = new Bundle{
    val input = slave(Axi4Shared(axiConfig))
    val readOutputs   = Vec(master(Axi4ReadOnly(axiConfig)),readDecodings.size)
    val writeOutputs  = Vec(master(Axi4WriteOnly(axiConfig)),writeDecodings.size)
    val sharedOutputs = Vec(master(Axi4Shared(axiConfig)),sharedDecodings.size)
  }

  //Used to allow writeData to be transmited before or in the same time than the corresponding writeCmd
  val dataOvertakeCmdEvent = False
  val dataOvertakeCmd = RegInit(False)  clearWhen(io.input.sharedCmd.fire) setWhen(dataOvertakeCmdEvent)

  val pendingCmdCounter = CounterMultiRequest(
    log2Up(pendingMax+1),
    (io.input.sharedCmd.fire -> (_ + 1)),
    (io.input.writeRsp.fire -> (_ - 1)),
    ((io.input.readRsp.fire && io.input.readRsp.last) -> (_ - 1))
  )

  val pendingDataCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = (io.input.sharedCmd.fire && io.input.sharedCmd.write && !dataOvertakeCmd) || dataOvertakeCmdEvent,
    decWhen = io.input.writeData.fire && io.input.writeData.last
  )

  val decodings = readDecodings ++ writeDecodings ++ sharedDecodings
  val readRange   = 0 to readDecodings.size -1
  val writeRange  = readRange.high + 1 to readRange.high + writeDecodings.size
  val sharedRange = writeRange.high + 1 to writeRange.high + sharedDecodings.size

  val decodedCmdSels,appliedCmdSels = Bits(decodings.size bits)
  val lastCmdSels  = RegNextWhen(appliedCmdSels,io.input.sharedCmd.ready)  init(0)
  val allowCmd    = pendingCmdCounter =/= pendingMax && (pendingCmdCounter === 0  || (lastCmdSels === decodedCmdSels))
  val allowData   = pendingDataCounter =/= 0 || (io.input.sharedCmd.valid && io.input.sharedCmd.write && ! dataOvertakeCmd)
  decodedCmdSels := Cat(
    sharedDecodings.map(_.hit(io.input.sharedCmd.addr) && io.input.sharedCmd.valid).asBits,
    writeDecodings.map(_.hit(io.input.sharedCmd.addr)  && io.input.sharedCmd.valid &&  io.input.sharedCmd.write).asBits,
    readDecodings.map(_.hit(io.input.sharedCmd.addr)   && io.input.sharedCmd.valid && !io.input.sharedCmd.write).asBits
  )
  appliedCmdSels := allowCmd ? decodedCmdSels | 0

  when(io.input.sharedCmd.fire){
    dataOvertakeCmd := False
  }
  when(io.input.writeData.fire && !io.input.sharedCmd.ready && pendingCmdCounter === 0 && !dataOvertakeCmd){
    dataOvertakeCmdEvent := True
  }


  io.input.sharedCmd.ready := (appliedCmdSels & (io.readOutputs.map(_.readCmd.ready) ++ io.writeOutputs.map(_.writeCmd.ready) ++ io.sharedOutputs.map(_.sharedCmd.ready)).asBits).orR
  //Wire readCmd
  for((output,sel) <- (io.readOutputs,appliedCmdSels(readRange).asBools).zipped){
    output.readCmd.valid := sel
    output.readCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }
  //Wire writeCmd
  for((output,sel) <- (io.writeOutputs,appliedCmdSels(writeRange).asBools).zipped){
    output.writeCmd.valid := sel
    output.writeCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }
  //Wire sharedCmd
  for((output,sel) <- (io.sharedOutputs,appliedCmdSels(sharedRange).asBools).zipped){
    output.sharedCmd.valid := sel
    output.sharedCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }

//  io.input.writeData.ready := (lastCmdSels(sharedRange) ## (lastCmdSels(writeRange)) & (io.writeOutputs.map(_.writeData.ready) ++ io.sharedOutputs.map(_.writeData.ready)).asBits).orR && allowData
//  //Wire writeWriteData
//  for((output,lastCmdSel,appliedCmdSel) <- (io.writeOutputs,lastCmdSels(writeRange).asBools,appliedCmdSels(writeRange).asBools).zipped){
//    output.writeData.valid   := io.input.writeData.valid && allowData && (lastCmdSel || (appliedCmdSel && io.input.arw.write))
//    output.writeData.payload := io.input.writeData.payload
//  }
//  //Wire sharedWriteData
//  for((output,lastCmdSel,appliedCmdSel) <- (io.sharedOutputs,lastCmdSels(sharedRange).asBools,appliedCmdSels(sharedRange).asBools).zipped){
//    output.writeData.valid   := io.input.writeData.valid && allowData && (lastCmdSel || (appliedCmdSel && io.input.arw.write))
//    output.writeData.payload := io.input.writeData.payload
//  }

  val writeDataSels = (lastCmdSels | appliedCmdSels)
  io.input.writeData.ready := (writeDataSels(sharedRange) ## (writeDataSels(writeRange)) & (io.writeOutputs.map(_.writeData.ready) ++ io.sharedOutputs.map(_.writeData.ready)).asBits).orR && allowData
  //Wire writeWriteData
  for((output,writeDataSel) <- (io.writeOutputs,writeDataSels(writeRange).asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && allowData && writeDataSel
    output.writeData.payload := io.input.writeData.payload
  }
  //Wire sharedWriteData
  for((output,writeDataSel) <- (io.sharedOutputs,writeDataSels(sharedRange).asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && allowData && writeDataSel
    output.writeData.payload := io.input.writeData.payload
  }

  //Wire writeRsp
  val writeRspIndex = OHToUInt(lastCmdSels(sharedRange) ## lastCmdSels(writeRange))
  io.input.writeRsp.valid :=   (io.writeOutputs.map(_.writeRsp.valid)   ++ io.sharedOutputs.map(_.writeRsp.valid)).asBits.orR
  io.input.writeRsp.payload := (io.writeOutputs.map(_.writeRsp.payload) ++ io.sharedOutputs.map(_.writeRsp.payload)).apply(writeRspIndex)
  io.writeOutputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)
  io.sharedOutputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)

  //Wire ReadRsp
  val readRspIndex = OHToUInt(lastCmdSels(sharedRange) ## lastCmdSels(readRange))
  io.input.readRsp.valid   := (io.readOutputs.map(_.readRsp.valid)   ++ io.sharedOutputs.map(_.readRsp.valid)).asBits.orR
  io.input.readRsp.payload := (io.readOutputs.map(_.readRsp.payload) ++ io.sharedOutputs.map(_.readRsp.payload)).apply(readRspIndex)
  io.readOutputs.foreach(_.readRsp.ready := io.input.readRsp.ready)
  io.sharedOutputs.foreach(_.readRsp.ready := io.input.readRsp.ready)


  //Decoding error managment
  val decodingErrorPossible = (writeDecodings ++ sharedDecodings).map(_.size).sum < (BigInt(1) << axiConfig.addressWidth) || (readDecodings ++ sharedDecodings).map(_.size).sum < (BigInt(1) << axiConfig.addressWidth)
  val decodingError = if(decodingErrorPossible) new Area{
    val detected = decodedCmdSels === 0 && io.input.sharedCmd.valid
    val waitLastDataWrite = RegInit(False)
    val sendWriteRsp = RegInit(False)
    val id = if(axiConfig.useId) Reg(UInt(axiConfig.idWidth bits)) else null
    val sendReadRsp = RegInit(False)
    val remaining = Reg(UInt(8 bits))
    val remainingZero = remaining === 0


    //Wait until all pending commands are done
    when(detected && pendingCmdCounter === 0){
      io.input.sharedCmd.ready := True
      if(id != null) id := io.input.sharedCmd.id
      remaining := (if(axiConfig.useLen) io.input.sharedCmd.len else U(0))
      waitLastDataWrite :=  io.input.sharedCmd.write
      sendReadRsp           := !io.input.sharedCmd.write
    }

    //Consume all writeData transaction
    when(waitLastDataWrite){
      io.input.writeData.ready := True
      when(pendingDataCounter === 0){
        io.input.writeData.ready := False
        waitLastDataWrite := False
        sendWriteRsp := True
      }
      allowCmd := False
    }

    //Send a DECERR writeRsp
    when(sendWriteRsp) {
      io.input.writeRsp.valid := True
      if(axiConfig.useResp) io.input.writeRsp.setDECERR
      if(id != null) io.input.writeRsp.id := id
      when(io.input.writeRsp.ready) {
        sendWriteRsp := False
      }
      allowCmd := False
    }

    //Send a DECERR readRsp
    when(sendReadRsp) {
      io.input.readRsp.valid := True
      if(axiConfig.useResp) io.input.readRsp.setDECERR
      if(id != null) io.input.readRsp.id := id
      if(io.input.readRsp.last != null) io.input.readRsp.last := remainingZero
      when(io.input.readRsp.ready) {
        remaining := remaining - 1
        when(remainingZero) {
          sendReadRsp := False
        }
      }
      allowCmd := False
    }
  }
}