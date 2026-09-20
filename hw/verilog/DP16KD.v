// A behavioural model of the ECP5 DP16KD block RAM, for simulation only.
//
// yosys ships the cell as an empty stub, so a design that instantiates it —
// which is the only way to get the cell's output register, since yosys 0.33
// maps every inferred memory with REGMODE=NOREG — has nothing to simulate
// against. This is that model, written from the datasheet's description of
// the cell and covering what the core uses of it: two ports, nine or
// eighteen bits wide, NORMAL write mode, the output register on or off, chip
// selects tied to zero and no reset. It is a reading of the datasheet, not
// the vendor's model, and the tests that use it check the wiring around the
// cell rather than the cell itself.
//
// Address pins carry a bit address: at eighteen bits wide the row is
// AD[13:4], at nine it is AD[13:3]. The array is 18,432 bits either way.
module DP16KD (
  input DIA17, DIA16, DIA15, DIA14, DIA13, DIA12, DIA11, DIA10, DIA9, DIA8, DIA7, DIA6, DIA5, DIA4, DIA3, DIA2, DIA1, DIA0,
  input ADA13, ADA12, ADA11, ADA10, ADA9, ADA8, ADA7, ADA6, ADA5, ADA4, ADA3, ADA2, ADA1, ADA0,
  input CEA, OCEA, CLKA, WEA, RSTA,
  input CSA2, CSA1, CSA0,
  output DOA17, DOA16, DOA15, DOA14, DOA13, DOA12, DOA11, DOA10, DOA9, DOA8, DOA7, DOA6, DOA5, DOA4, DOA3, DOA2, DOA1, DOA0,
  input DIB17, DIB16, DIB15, DIB14, DIB13, DIB12, DIB11, DIB10, DIB9, DIB8, DIB7, DIB6, DIB5, DIB4, DIB3, DIB2, DIB1, DIB0,
  input ADB13, ADB12, ADB11, ADB10, ADB9, ADB8, ADB7, ADB6, ADB5, ADB4, ADB3, ADB2, ADB1, ADB0,
  input CEB, OCEB, CLKB, WEB, RSTB,
  input CSB2, CSB1, CSB0,
  output DOB17, DOB16, DOB15, DOB14, DOB13, DOB12, DOB11, DOB10, DOB9, DOB8, DOB7, DOB6, DOB5, DOB4, DOB3, DOB2, DOB1, DOB0
);
  parameter DATA_WIDTH_A = 18;
  parameter DATA_WIDTH_B = 18;
  parameter REGMODE_A = "NOREG";
  parameter REGMODE_B = "NOREG";
  parameter RESETMODE = "SYNC";
  parameter ASYNC_RESET_RELEASE = "SYNC";
  parameter CSDECODE_A = "0b000";
  parameter CSDECODE_B = "0b000";
  parameter WRITEMODE_A = "NORMAL";
  parameter WRITEMODE_B = "NORMAL";
  parameter GSR = "DISABLED";
  parameter CLKAMUX = "CLKA";
  parameter CLKBMUX = "CLKB";
  parameter INIT_DATA = "STATIC";

  // The array as 2048 rows of 9 bits, which is the finest organisation the
  // core asks for; an 18-bit row is two of them.
  reg [8:0] mem [0:2047];
  integer i;
  initial for (i = 0; i < 2048; i = i + 1) mem[i] = 9'd0;

  wire [13:0] ada = {ADA13, ADA12, ADA11, ADA10, ADA9, ADA8, ADA7, ADA6, ADA5, ADA4, ADA3, ADA2, ADA1, ADA0};
  wire [13:0] adb = {ADB13, ADB12, ADB11, ADB10, ADB9, ADB8, ADB7, ADB6, ADB5, ADB4, ADB3, ADB2, ADB1, ADB0};
  wire [17:0] dia = {DIA17, DIA16, DIA15, DIA14, DIA13, DIA12, DIA11, DIA10, DIA9, DIA8, DIA7, DIA6, DIA5, DIA4, DIA3, DIA2, DIA1, DIA0};
  wire [17:0] dib = {DIB17, DIB16, DIB15, DIB14, DIB13, DIB12, DIB11, DIB10, DIB9, DIB8, DIB7, DIB6, DIB5, DIB4, DIB3, DIB2, DIB1, DIB0};

  wire [10:0] rowa = (DATA_WIDTH_A == 18) ? {ada[13:4], 1'b0} : ada[13:3];
  wire [10:0] rowb = (DATA_WIDTH_B == 18) ? {adb[13:4], 1'b0} : adb[13:3];

  reg [17:0] arraya = 18'd0;
  reg [17:0] arrayb = 18'd0;
  reg [17:0] outa = 18'd0;
  reg [17:0] outb = 18'd0;

  // NORMAL write mode: the read output is not updated by a write on the same
  // port. Everything happens on the port's own clock.
  always @(posedge CLKA) begin
    if (CEA) begin
      if (WEA) begin
        mem[rowa] <= dia[8:0];
        if (DATA_WIDTH_A == 18) mem[rowa + 1] <= dia[17:9];
      end else begin
        arraya <= (DATA_WIDTH_A == 18) ? {mem[rowa + 1], mem[rowa]} : {9'd0, mem[rowa]};
      end
    end
    if (OCEA) outa <= arraya;
  end

  always @(posedge CLKB) begin
    if (CEB) begin
      if (WEB) begin
        mem[rowb] <= dib[8:0];
        if (DATA_WIDTH_B == 18) mem[rowb + 1] <= dib[17:9];
      end else begin
        arrayb <= (DATA_WIDTH_B == 18) ? {mem[rowb + 1], mem[rowb]} : {9'd0, mem[rowb]};
      end
    end
    if (OCEB) outb <= arrayb;
  end

  wire [17:0] doa = (REGMODE_A == "OUTREG") ? outa : arraya;
  wire [17:0] dob = (REGMODE_B == "OUTREG") ? outb : arrayb;
  assign {DOA17, DOA16, DOA15, DOA14, DOA13, DOA12, DOA11, DOA10, DOA9, DOA8, DOA7, DOA6, DOA5, DOA4, DOA3, DOA2, DOA1, DOA0} = doa;
  assign {DOB17, DOB16, DOB15, DOB14, DOB13, DOB12, DOB11, DOB10, DOB9, DOB8, DOB7, DOB6, DOB5, DOB4, DOB3, DOB2, DOB1, DOB0} = dob;
endmodule
