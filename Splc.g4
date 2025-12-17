//lexer grammar Splc;
grammar Splc;

// Changes in Project 2: Splc.g4 contains both parser rules and lexer rules.
//  so there should be "grammar Splc;' instead of 'lexer grammer Splc;'

// IDEA Plugin Settings
// - Output Directory: src/main/java/
// - package name: generated.Splc

// =========================
// Parser Rules
// =========================

program
    : globalDef* EOF;

globalDef
    : specifier Identifier LPAREN funcArgs RPAREN LBRACE statement* RBRACE          // 函数定义
    | specifier Identifier LPAREN funcArgs RPAREN SEMI                              // 函数声明 (新增)
    | specifier varDec SEMI     // 全局变量定义
    | specifier SEMI  // 全局结构体声明
    ;

// 2.2 类型说明符 (specifier)
specifier
    : INT                          // 整型
    | CHAR                         // 字符型
    | STRUCT Identifier            // 结构体
    | STRUCT Identifier LBRACE (specifier varDec SEMI)* RBRACE  // 完整结构体
    ;

// 2.3 变量声明 (varDec)
varDec
    : Identifier                          // 简单变量声明
    | LPAREN varDec RPAREN                // 括号改变结合顺序
    | varDec LBRACK Number RBRACK         // 数组声明（[]优先级高于*）
    | STAR varDec                         // 指针声明
    ;

// 2.4 函数参数 (funcArgs)
funcArgs
    : (specifier varDec (COMMA specifier varDec)*)?  // 参数列表
    ;

// 2.5 语句 (statement)
statement
    : LBRACE statement* RBRACE                                    #BlockStmt  // 代码块
    | specifier varDec (ASSIGN expression)? SEMI                  #VarDecStmt  // 局部变量定义
    | IF LPAREN expression RPAREN statement (ELSE statement)?     #IfStmt  // If语句
    | WHILE LPAREN expression RPAREN statement                    #WhileStmt  // While语句
    | RETURN expression SEMI                                      #ReturnStmt  // 返回语句
    | expression SEMI                                             #ExpressionStmt  // 表达式语句
    ;

// 2.6 表达式 (expression)
expression
    : Identifier                                                                # ExprId
    | Number                                                                    # ExprInt
    | Char                                                                      # ExprChar
    | LPAREN expression RPAREN                                                  # ExprParens
    | expression (INC | DEC)                                                    # ExprPostfix
    | Identifier LPAREN (expression (COMMA expression)*)? RPAREN                # ExprCall
    | expression LBRACK expression RBRACK                                       # ExprArray
    | expression DOT Identifier                                                 # ExprDot
    | expression ARROW Identifier                                               # ExprArrow
    | <assoc=right> (INC | DEC | PLUS | MINUS | NOT | AMP | STAR) expression    # ExprPrefix
    | expression (STAR | DIV | MOD) expression                                  # ExprMulDivMod
    | expression (PLUS | MINUS) expression                                      # ExprAddSub
    | expression (LT | LE | GT | GE | EQ | NEQ) expression                      # ExprRel
    | expression AND expression                                                 # ExprAnd
    | expression OR expression                                                  # ExprOr
    | <assoc=right> expression ASSIGN expression                                # ExprAssign
    ;

// =========================
// Lexer Rules
// =========================

// ---------- Keywords ----------
INT     : 'int';
CHAR    : 'char';
STRUCT  : 'struct';
RETURN  : 'return';
IF      : 'if';
ELSE    : 'else';
WHILE   : 'while';

// ---------- Operators ----------
ASSIGN  : '=';
PLUS    : '+';
MINUS   : '-';
STAR    : '*';
DIV     : '/';
MOD     : '%';
LT      : '<';
LE      : '<=';
GT      : '>';
GE      : '>=';
EQ      : '==';
NEQ     : '!=';
AND     : '&&';
OR      : '||';
NOT     : '!';
INC     : '++';
DEC     : '--';
DOT     : '.';
ARROW   : '->';
AMP     : '&';

// ---------- Separators ----------
SEMI    : ';';
COMMA   : ',';
LPAREN  : '(';
RPAREN  : ')';
LBRACE  : '{';
RBRACE  : '}';
LBRACK  : '[';
RBRACK  : ']';

// ---------- Identifiers & Literals ----------
Identifier  : [a-zA-Z_][a-zA-Z_0-9]*;
Number      : [1-9][0-9]* | '0';
Char        : '\'' (~['\\] | '\\'[nt0'\\]) '\'';

// ---------- Whitespace & Comments ----------
WS             : [ \t\r\n]+ -> skip;
LINE_COMMENT   : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT  : '/*' .*? '*/' -> skip;