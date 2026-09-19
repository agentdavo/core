// One DP16KD, 18 bits wide, address registered in, data registered out.
module ebr_OUTREG(input clk, input [9:0] a_in, input [17:0] d_in, input we_in, output reg [17:0] q);
  reg [9:0] a; reg [17:0] d; reg we;
  always @(posedge clk) begin a <= a_in; d <= d_in; we <= we_in; end
  wire [17:0] dout;
  DP16KD #(
    .DATA_WIDTH_A(18), .DATA_WIDTH_B(18),
    .REGMODE_A("OUTREG"), .REGMODE_B("OUTREG"),
    .CLKAMUX("CLKA"), .CLKBMUX("CLKB"),
    .WRITEMODE_A("NORMAL"), .WRITEMODE_B("NORMAL"),
    .GSR("DISABLED")
  ) ram (
    .CLKA(clk), .CLKB(clk),
    .CEA(1'b1), .CEB(1'b1), .OCEA(1'b1), .OCEB(1'b1),
    .RSTA(1'b0), .RSTB(1'b0),
    .WEA(we), .WEB(1'b0),
    .CSA0(1'b0), .CSA1(1'b0), .CSA2(1'b0), .CSB0(1'b0), .CSB1(1'b0), .CSB2(1'b0),
    .ADA0(1'b0), .ADA1(1'b0), .ADA2(1'b0), .ADA3(1'b0), .ADA4(a[0]), .ADA5(a[1]), .ADA6(a[2]), .ADA7(a[3]),
    .ADA8(a[4]), .ADA9(a[5]), .ADA10(a[6]), .ADA11(a[7]), .ADA12(a[8]), .ADA13(a[9]),
    .ADB0(1'b0), .ADB1(1'b0), .ADB2(1'b0), .ADB3(1'b0), .ADB4(a[0]), .ADB5(a[1]), .ADB6(a[2]), .ADB7(a[3]),
    .ADB8(a[4]), .ADB9(a[5]), .ADB10(a[6]), .ADB11(a[7]), .ADB12(a[8]), .ADB13(a[9]),
    .DIA0(d[0]), .DIA1(d[1]), .DIA2(d[2]), .DIA3(d[3]), .DIA4(d[4]), .DIA5(d[5]), .DIA6(d[6]), .DIA7(d[7]), .DIA8(d[8]),
    .DIA9(d[9]), .DIA10(d[10]), .DIA11(d[11]), .DIA12(d[12]), .DIA13(d[13]), .DIA14(d[14]), .DIA15(d[15]), .DIA16(d[16]), .DIA17(d[17]),
    .DIB0(1'b0), .DIB1(1'b0), .DIB2(1'b0), .DIB3(1'b0), .DIB4(1'b0), .DIB5(1'b0), .DIB6(1'b0), .DIB7(1'b0), .DIB8(1'b0),
    .DIB9(1'b0), .DIB10(1'b0), .DIB11(1'b0), .DIB12(1'b0), .DIB13(1'b0), .DIB14(1'b0), .DIB15(1'b0), .DIB16(1'b0), .DIB17(1'b0),
    .DOB0(dout[0]), .DOB1(dout[1]), .DOB2(dout[2]), .DOB3(dout[3]), .DOB4(dout[4]), .DOB5(dout[5]), .DOB6(dout[6]), .DOB7(dout[7]), .DOB8(dout[8]),
    .DOB9(dout[9]), .DOB10(dout[10]), .DOB11(dout[11]), .DOB12(dout[12]), .DOB13(dout[13]), .DOB14(dout[14]), .DOB15(dout[15]), .DOB16(dout[16]), .DOB17(dout[17])
  );
  always @(posedge clk) q <= dout;
endmodule
