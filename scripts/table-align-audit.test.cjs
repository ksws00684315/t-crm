const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const { classifyAlignment } = require("./table-align-audit.js");

describe("classifyAlignment", () => {
  it("flags a centered box whose content sits more than 2px off the cell center", () => {
    const problems = classifyAlignment({
      align: "center",
      contentStart: 10,
      contentEnd: 30,
      cellStart: 0,
      cellEnd: 100,
      headerAlign: "center",
      cellAlign: "center"
    });
    assert.deepEqual(
      problems.map(item => item.reason),
      ["center"]
    );
    assert.ok(problems[0].deltaPx > 2);
  });

  it("flags left-aligned content that does not start at the content box", () => {
    const problems = classifyAlignment({
      align: "left",
      contentStart: 12,
      contentEnd: 40,
      cellStart: 0,
      cellEnd: 100,
      headerAlign: "left",
      cellAlign: "left"
    });
    assert.deepEqual(
      problems.map(item => item.reason),
      ["left"]
    );
    assert.equal(problems[0].deltaPx, 12);
  });

  it("flags a header whose direction differs from the cell", () => {
    const problems = classifyAlignment({
      align: "left",
      contentStart: 0,
      contentEnd: 40,
      cellStart: 0,
      cellEnd: 100,
      headerAlign: "center",
      cellAlign: "left"
    });
    assert.deepEqual(
      problems.map(item => item.reason),
      ["direction"]
    );
  });

  it("accepts content inside the 2px tolerance", () => {
    assert.deepEqual(
      classifyAlignment({
        align: "center",
        contentStart: 39,
        contentEnd: 59,
        cellStart: 0,
        cellEnd: 100,
        headerAlign: "center",
        cellAlign: "center"
      }),
      []
    );
  });
});
